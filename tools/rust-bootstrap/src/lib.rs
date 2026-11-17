//! Native Rust ARM64 bootstrap & prefix engine for DeepSeek Android (`libds_bootstrap.so`).
//!
//! Zero external crate dependencies: uses Rust `std` + Android Bionic `mmap`/`fchmod`
//! + `libzstd.so` streaming FFI to extract ZIP_STORED `.tar.zst` archives directly from
//! the APK in ~1.5-3s, verify SHA-256 in memory, and rewrite Bionic prefix paths/shebangs.

use std::collections::HashSet;
use std::ffi::{c_char, c_int, c_void, CStr};
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::os::unix::io::AsRawFd;
use std::panic;
use std::path::{Path, PathBuf};
use std::ptr;
use std::slice;

const PROT_READ: c_int = 1;
const MAP_PRIVATE: c_int = 2;
const MADV_SEQUENTIAL: c_int = 2;

extern "C" {
    fn mmap(
        addr: *mut c_void,
        len: usize,
        prot: c_int,
        flags: c_int,
        fd: c_int,
        offset: i64,
    ) -> *mut c_void;
    fn munmap(addr: *mut c_void, len: usize) -> c_int;
    fn madvise(addr: *mut c_void, len: usize, advice: c_int) -> c_int;
    fn fchmod(fd: c_int, mode: u32) -> c_int;
}

#[repr(C)]
struct ZstdInBuffer {
    src: *const c_void,
    size: usize,
    pos: usize,
}

#[repr(C)]
struct ZstdOutBuffer {
    dst: *mut c_void,
    size: usize,
    pos: usize,
}

#[link(name = "zstd")]
extern "C" {
    fn ZSTD_createDCtx() -> *mut c_void;
    fn ZSTD_freeDCtx(dctx: *mut c_void) -> usize;
    fn ZSTD_decompressStream(
        zds: *mut c_void,
        output: *mut ZstdOutBuffer,
        input: *mut ZstdInBuffer,
    ) -> usize;
    fn ZSTD_isError(code: usize) -> u32;
    fn ZSTD_getErrorName(code: usize) -> *const c_char;
}

struct MmapFile {
    ptr: *mut c_void,
    len: usize,
}

impl MmapFile {
    fn open(path: &Path) -> Result<Self, String> {
        let file = File::open(path)
            .map_err(|e| format!("cannot open {}: {}", path.display(), e))?;
        let meta = file
            .metadata()
            .map_err(|e| format!("cannot stat {}: {}", path.display(), e))?;
        let len = meta.len() as usize;
        if len == 0 {
            return Err(format!("empty file: {}", path.display()));
        }
        let ptr = unsafe {
            mmap(
                ptr::null_mut(),
                len,
                PROT_READ,
                MAP_PRIVATE,
                file.as_raw_fd(),
                0,
            )
        };
        if ptr == (usize::MAX as *mut c_void) || ptr.is_null() {
            return Err(format!("mmap failed for {}", path.display()));
        }
        unsafe {
            let _ = madvise(ptr, len, MADV_SEQUENTIAL);
        }
        Ok(Self { ptr, len })
    }

    fn as_slice(&self) -> &[u8] {
        unsafe { slice::from_raw_parts(self.ptr as *const u8, self.len) }
    }
}

impl Drop for MmapFile {
    fn drop(&mut self) {
        if !self.ptr.is_null() && self.len > 0 {
            unsafe {
                let _ = munmap(self.ptr, self.len);
            }
        }
    }
}

struct ZstdCtx(*mut c_void);

impl ZstdCtx {
    fn new() -> Result<Self, String> {
        let ptr = unsafe { ZSTD_createDCtx() };
        if ptr.is_null() {
            Err("ZSTD_createDCtx returned null".to_string())
        } else {
            Ok(Self(ptr))
        }
    }
}

impl Drop for ZstdCtx {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                let _ = ZSTD_freeDCtx(self.0);
            }
        }
    }
}

// ============================================================================
// Pure-Rust SHA-256 (FIPS 180-4, zero dependencies, portable ARMv8)
// ============================================================================

const K256: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

#[inline(always)]
fn sha256_compress(state: &mut [u32; 8], block: &[u8; 64]) {
    let mut w = [0u32; 64];
    for i in 0..16 {
        w[i] = u32::from_be_bytes([
            block[i * 4],
            block[i * 4 + 1],
            block[i * 4 + 2],
            block[i * 4 + 3],
        ]);
    }
    for i in 16..64 {
        let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
        let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
        w[i] = w[i - 16]
            .wrapping_add(s0)
            .wrapping_add(w[i - 7])
            .wrapping_add(s1);
    }

    let mut a = state[0];
    let mut b = state[1];
    let mut c = state[2];
    let mut d = state[3];
    let mut e = state[4];
    let mut f = state[5];
    let mut g = state[6];
    let mut h = state[7];

    for i in 0..64 {
        let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
        let ch = (e & f) ^ ((!e) & g);
        let temp1 = h
            .wrapping_add(s1)
            .wrapping_add(ch)
            .wrapping_add(K256[i])
            .wrapping_add(w[i]);
        let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
        let maj = (a & b) ^ (a & c) ^ (b & c);
        let temp2 = s0.wrapping_add(maj);

        h = g;
        g = f;
        f = e;
        e = d.wrapping_add(temp1);
        d = c;
        c = b;
        b = a;
        a = temp1.wrapping_add(temp2);
    }

    state[0] = state[0].wrapping_add(a);
    state[1] = state[1].wrapping_add(b);
    state[2] = state[2].wrapping_add(c);
    state[3] = state[3].wrapping_add(d);
    state[4] = state[4].wrapping_add(e);
    state[5] = state[5].wrapping_add(f);
    state[6] = state[6].wrapping_add(g);
    state[7] = state[7].wrapping_add(h);
}

fn sha256_hex(data: &[u8]) -> String {
    let mut state: [u32; 8] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ];
    let mut chunks = data.chunks_exact(64);
    for chunk in &mut chunks {
        let block: &[u8; 64] = chunk.try_into().unwrap();
        sha256_compress(&mut state, block);
    }
    let rem = chunks.remainder();
    let mut tail = [0u8; 128];
    tail[..rem.len()].copy_from_slice(rem);
    tail[rem.len()] = 0x80;
    let bit_len = (data.len() as u64) * 8;
    if rem.len() < 56 {
        tail[56..64].copy_from_slice(&bit_len.to_be_bytes());
        sha256_compress(&mut state, tail[0..64].try_into().unwrap());
    } else {
        tail[120..128].copy_from_slice(&bit_len.to_be_bytes());
        sha256_compress(&mut state, tail[0..64].try_into().unwrap());
        sha256_compress(&mut state, tail[64..128].try_into().unwrap());
    }
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(64);
    for word in state {
        for byte in word.to_be_bytes() {
            out.push(HEX[(byte >> 4) as usize] as char);
            out.push(HEX[(byte & 0x0f) as usize] as char);
        }
    }
    out
}

// ============================================================================
// ZIP Central Directory Locator (locates ZIP_STORED entry slice inside APK)
// ============================================================================

#[inline]
fn read_u16_le(b: &[u8], off: usize) -> u16 {
    u16::from_le_bytes([b[off], b[off + 1]])
}

#[inline]
fn read_u32_le(b: &[u8], off: usize) -> u32 {
    u32::from_le_bytes([b[off], b[off + 1], b[off + 2], b[off + 3]])
}

#[inline]
fn read_u64_le(b: &[u8], off: usize) -> u64 {
    u64::from_le_bytes([
        b[off],
        b[off + 1],
        b[off + 2],
        b[off + 3],
        b[off + 4],
        b[off + 5],
        b[off + 6],
        b[off + 7],
    ])
}

fn find_apk_stored_slice<'a>(apk: &'a [u8], target_entry: &str) -> Result<&'a [u8], String> {
    if apk.len() < 22 {
        return Err("APK too small to be a valid ZIP".to_string());
    }
    let max_back = apk.len().min(65557);
    let search_start = apk.len() - max_back;
    let mut eocd_off = None;
    let mut i = apk.len() - 22;
    loop {
        if read_u32_le(apk, i) == 0x06054b50 {
            let comment_len = read_u16_le(apk, i + 20) as usize;
            if i + 22 + comment_len == apk.len() {
                eocd_off = Some(i);
                break;
            }
        }
        if i == search_start {
            break;
        }
        i -= 1;
    }
    let eocd = eocd_off.ok_or_else(|| "ZIP End of Central Directory not found".to_string())?;

    let mut cd_entries = read_u16_le(apk, eocd + 10) as u64;
    let mut cd_offset = read_u32_le(apk, eocd + 16) as u64;

    if (cd_entries == 0xFFFF || cd_offset == 0xFFFFFFFF) && eocd >= 20 {
        let loc = eocd - 20;
        if read_u32_le(apk, loc) == 0x07064b50 {
            let zip64_eocd = read_u64_le(apk, loc + 8) as usize;
            if zip64_eocd + 56 <= apk.len() && read_u32_le(apk, zip64_eocd) == 0x06064b50 {
                cd_entries = read_u64_le(apk, zip64_eocd + 32);
                cd_offset = read_u64_le(apk, zip64_eocd + 48);
            }
        }
    }

    let mut pos = cd_offset as usize;
    let target_bytes = target_entry.as_bytes();

    for _ in 0..cd_entries {
        if pos + 46 > apk.len() || read_u32_le(apk, pos) != 0x02014b50 {
            return Err("corrupt ZIP Central Directory entry".to_string());
        }
        let method = read_u16_le(apk, pos + 10);
        let mut comp_size = read_u32_le(apk, pos + 20) as u64;
        let uncomp_size = read_u32_le(apk, pos + 24) as u64;
        let name_len = read_u16_le(apk, pos + 28) as usize;
        let extra_len = read_u16_le(apk, pos + 30) as usize;
        let comment_len = read_u16_le(apk, pos + 32) as usize;
        let mut local_hdr_off = read_u32_le(apk, pos + 42) as u64;

        let name_start = pos + 46;
        let extra_start = name_start + name_len;
        let next_pos = extra_start + extra_len + comment_len;
        if next_pos > apk.len() {
            return Err("truncated ZIP Central Directory entry".to_string());
        }

        if &apk[name_start..extra_start] == target_bytes {
            if comp_size == 0xFFFFFFFF || uncomp_size == 0xFFFFFFFF || local_hdr_off == 0xFFFFFFFF {
                let mut ex = extra_start;
                while ex + 4 <= extra_start + extra_len {
                    let header_id = read_u16_le(apk, ex);
                    let data_sz = read_u16_le(apk, ex + 2) as usize;
                    let data_end = (ex + 4 + data_sz).min(extra_start + extra_len);
                    if header_id == 0x0001 {
                        let mut cur = ex + 4;
                        if uncomp_size == 0xFFFFFFFF && cur + 8 <= data_end {
                            let _ = read_u64_le(apk, cur);
                            cur += 8;
                        }
                        if comp_size == 0xFFFFFFFF && cur + 8 <= data_end {
                            comp_size = read_u64_le(apk, cur);
                            cur += 8;
                        }
                        if local_hdr_off == 0xFFFFFFFF && cur + 8 <= data_end {
                            local_hdr_off = read_u64_le(apk, cur);
                        }
                        break;
                    }
                    ex += 4 + data_sz;
                }
            }
            if method != 0 {
                return Err(format!(
                    "APK entry {} is compressed with method {} (expected ZIP_STORED=0)",
                    target_entry, method
                ));
            }
            let lhoff = local_hdr_off as usize;
            if lhoff + 30 > apk.len() || read_u32_le(apk, lhoff) != 0x04034b50 {
                return Err(format!("invalid ZIP local header for {}", target_entry));
            }
            let lname_len = read_u16_le(apk, lhoff + 26) as usize;
            let lextra_len = read_u16_le(apk, lhoff + 28) as usize;
            let data_start = lhoff + 30 + lname_len + lextra_len;
            let data_end = data_start
                .checked_add(comp_size as usize)
                .ok_or_else(|| "integer overflow in ZIP entry range".to_string())?;
            if data_end > apk.len() {
                return Err(format!("ZIP entry {} exceeds APK size", target_entry));
            }
            return Ok(&apk[data_start..data_end]);
        }

        pos = next_pos;
    }

    Err(format!("APK entry not found: {}", target_entry))
}

// ============================================================================
// Streaming USTAR / PAX / GNU Tar Extractor
// ============================================================================

enum TarState {
    Header,
    MetaPayload {
        kind: u8,
        size: usize,
        pad: usize,
        buf: Vec<u8>,
    },
    FilePayload {
        file: File,
        remaining: usize,
        pad: usize,
    },
    SkipPadding(usize),
    Done,
}

struct TarExtractor {
    dest_root: PathBuf,
    state: TarState,
    hdr_buf: [u8; 512],
    hdr_pos: usize,
    zero_blocks: u8,
    pax_path: Option<String>,
    pax_linkpath: Option<String>,
    pax_size: Option<usize>,
    global_pax_path: Option<String>,
    global_pax_linkpath: Option<String>,
    gnu_longname: Option<String>,
    gnu_longlink: Option<String>,
    created_dirs: HashSet<PathBuf>,
    created_symlinks: HashSet<String>,
}

impl TarExtractor {
    fn new(dest_root: PathBuf) -> Result<Self, String> {
        fs::create_dir_all(&dest_root)
            .map_err(|e| format!("cannot create dest {}: {}", dest_root.display(), e))?;
        let canon = fs::canonicalize(&dest_root)
            .map_err(|e| format!("cannot canonicalize {}: {}", dest_root.display(), e))?;
        let mut created_dirs = HashSet::with_capacity(4096);
        created_dirs.insert(canon.clone());
        Ok(Self {
            dest_root: canon,
            state: TarState::Header,
            hdr_buf: [0u8; 512],
            hdr_pos: 0,
            zero_blocks: 0,
            pax_path: None,
            pax_linkpath: None,
            pax_size: None,
            global_pax_path: None,
            global_pax_linkpath: None,
            gnu_longname: None,
            gnu_longlink: None,
            created_dirs,
            created_symlinks: HashSet::with_capacity(256),
        })
    }

    fn feed(&mut self, mut chunk: &[u8]) -> Result<(), String> {
        while !chunk.is_empty() {
            match &mut self.state {
                TarState::Done => return Ok(()),
                TarState::Header => {
                    if self.hdr_pos == 0 && chunk.len() >= 512 {
                        let hdr: [u8; 512] = chunk[..512].try_into().unwrap();
                        chunk = &chunk[512..];
                        self.process_header(&hdr)?;
                    } else {
                        let need = 512 - self.hdr_pos;
                        let take = need.min(chunk.len());
                        self.hdr_buf[self.hdr_pos..self.hdr_pos + take]
                            .copy_from_slice(&chunk[..take]);
                        self.hdr_pos += take;
                        chunk = &chunk[take..];
                        if self.hdr_pos == 512 {
                            self.hdr_pos = 0;
                            let hdr = self.hdr_buf;
                            self.process_header(&hdr)?;
                        }
                    }
                }
                TarState::MetaPayload {
                    kind,
                    size,
                    pad,
                    buf,
                } => {
                    let rem = *size - buf.len();
                    if rem > 0 {
                        let take = rem.min(chunk.len());
                        buf.extend_from_slice(&chunk[..take]);
                        chunk = &chunk[take..];
                    }
                    if buf.len() == *size {
                        let k = *kind;
                        let p = *pad;
                        let payload = std::mem::take(buf);
                        self.apply_meta(k, &payload)?;
                        self.state = if p > 0 {
                            TarState::SkipPadding(p)
                        } else {
                            TarState::Header
                        };
                    }
                }
                TarState::FilePayload {
                    file,
                    remaining,
                    pad,
                } => {
                    let take = (*remaining).min(chunk.len());
                    if take > 0 {
                        file.write_all(&chunk[..take])
                            .map_err(|e| format!("tar write failed: {}", e))?;
                        *remaining -= take;
                        chunk = &chunk[take..];
                    }
                    if *remaining == 0 {
                        let p = *pad;
                        self.state = if p > 0 {
                            TarState::SkipPadding(p)
                        } else {
                            TarState::Header
                        };
                    }
                }
                TarState::SkipPadding(rem) => {
                    let take = (*rem).min(chunk.len());
                    *rem -= take;
                    chunk = &chunk[take..];
                    if *rem == 0 {
                        self.state = TarState::Header;
                    }
                }
            }
        }
        Ok(())
    }

    fn finish(self) -> Result<(), String> {
        match self.state {
            TarState::Header if self.hdr_pos == 0 => Ok(()),
            TarState::Done => Ok(()),
            TarState::SkipPadding(_) => Ok(()),
            _ => Err("truncated tar stream inside .tar.zst archive".to_string()),
        }
    }

    fn process_header(&mut self, hdr: &[u8; 512]) -> Result<(), String> {
        if hdr.iter().all(|&b| b == 0) {
            self.zero_blocks += 1;
            if self.zero_blocks >= 2 {
                self.state = TarState::Done;
            }
            return Ok(());
        }
        self.zero_blocks = 0;

        let mode = parse_tar_num(&hdr[100..108]) as u32;
        let mut size = parse_tar_num(&hdr[124..136]) as usize;
        let mut typeflag = hdr[156];
        if typeflag == 0 {
            typeflag = b'0';
        }

        if typeflag == b'L' || typeflag == b'K' || typeflag == b'x' || typeflag == b'g' {
            if size > (4 << 20) {
                return Err(format!("excessive tar metadata size: {}", size));
            }
            let pad = (512 - (size & 511)) & 511;
            self.state = TarState::MetaPayload {
                kind: typeflag,
                size,
                pad,
                buf: Vec::with_capacity(size),
            };
            return Ok(());
        }

        if let Some(ps) = self.pax_size.take() {
            size = ps;
        }
        let pad = (512 - (size & 511)) & 511;
        let padded_total = size + pad;

        let raw_name = if let Some(p) = self.pax_path.take() {
            p
        } else if let Some(p) = self.gnu_longname.take() {
            p
        } else if let Some(ref p) = self.global_pax_path {
            p.clone()
        } else {
            let name = read_c_field(&hdr[0..100]);
            let prefix = read_c_field(&hdr[345..500]);
            if prefix.is_empty() {
                name
            } else {
                format!("{}/{}", prefix, name)
            }
        };

        let raw_link = if let Some(l) = self.pax_linkpath.take() {
            l
        } else if let Some(l) = self.gnu_longlink.take() {
            l
        } else if let Some(ref l) = self.global_pax_linkpath {
            l.clone()
        } else {
            read_c_field(&hdr[157..257])
        };

        let norm_rel = match sanitize_rel_path(&raw_name, &self.created_symlinks)? {
            Some(r) => r,
            None => {
                self.state = if padded_total > 0 {
                    TarState::SkipPadding(padded_total)
                } else {
                    TarState::Header
                };
                return Ok(());
            }
        };

        let full_path = self.dest_root.join(&norm_rel);

        match typeflag {
            b'5' => {
                self.ensure_dir(&full_path)?;
                if (mode & 0o777) != 0 {
                    let _ = fs::set_permissions(
                        &full_path,
                        fs::Permissions::from_mode((mode & 0o777) | 0o700),
                    );
                }
                self.state = if padded_total > 0 {
                    TarState::SkipPadding(padded_total)
                } else {
                    TarState::Header
                };
            }
            b'0' | b'7' => {
                if let Some(parent) = full_path.parent() {
                    self.ensure_dir(parent)?;
                }
                let is_exec = (mode & 0o111) != 0
                    || norm_rel.starts_with("bin/")
                    || norm_rel.starts_with("lib/")
                    || norm_rel.starts_with("libexec/")
                    || norm_rel.ends_with(".so")
                    || norm_rel.ends_with(".node")
                    || norm_rel.ends_with("/rg")
                    || norm_rel.ends_with("/bin.js");
                let fmode = if is_exec { 0o755 } else { 0o644 };

                // Never follow a pre-existing symlink or directory at full_path
                if let Ok(existing) = fs::symlink_metadata(&full_path) {
                    if existing.file_type().is_symlink() || existing.file_type().is_dir() {
                        unlink_any(&full_path);
                        self.created_symlinks.remove(&norm_rel);
                    }
                }
                let file = match OpenOptions::new()
                    .write(true)
                    .create(true)
                    .truncate(true)
                    .mode(fmode)
                    .open(&full_path)
                {
                    Ok(f) => f,
                    Err(_) => {
                        unlink_any(&full_path);
                        OpenOptions::new()
                            .write(true)
                            .create(true)
                            .truncate(true)
                            .mode(fmode)
                            .open(&full_path)
                            .map_err(|e| {
                                format!("cannot create {}: {}", full_path.display(), e)
                            })?
                    }
                };
                if is_exec {
                    unsafe {
                        let _ = fchmod(file.as_raw_fd(), 0o755);
                    }
                }
                if size == 0 {
                    drop(file);
                    self.state = TarState::Header;
                } else {
                    self.state = TarState::FilePayload {
                        file,
                        remaining: size,
                        pad,
                    };
                }
            }
            b'2' => {
                if !raw_link.is_empty() && !raw_link.contains('\0') {
                    validate_symlink_target(&norm_rel, &raw_link)?;
                    if let Some(parent) = full_path.parent() {
                        self.ensure_dir(parent)?;
                    }
                    unlink_any(&full_path);
                    std::os::unix::fs::symlink(&raw_link, &full_path).map_err(|e| {
                        format!(
                            "cannot symlink {} -> {}: {}",
                            full_path.display(),
                            raw_link,
                            e
                        )
                    })?;
                    self.created_symlinks.insert(norm_rel);
                }
                self.state = if padded_total > 0 {
                    TarState::SkipPadding(padded_total)
                } else {
                    TarState::Header
                };
            }
            b'1' => {
                if let Some(target_rel) = sanitize_rel_path(&raw_link, &self.created_symlinks)? {
                    let src_path = self.dest_root.join(&target_rel);
                    if src_path.exists() {
                        if let Some(parent) = full_path.parent() {
                            self.ensure_dir(parent)?;
                        }
                        unlink_any(&full_path);
                        fs::copy(&src_path, &full_path).map_err(|e| {
                            format!(
                                "cannot copy hardlink {} -> {}: {}",
                                src_path.display(),
                                full_path.display(),
                                e
                            )
                        })?;
                    }
                }
                self.state = if padded_total > 0 {
                    TarState::SkipPadding(padded_total)
                } else {
                    TarState::Header
                };
            }
            _ => {
                self.state = if padded_total > 0 {
                    TarState::SkipPadding(padded_total)
                } else {
                    TarState::Header
                };
            }
        }
        Ok(())
    }

    fn apply_meta(&mut self, kind: u8, payload: &[u8]) -> Result<(), String> {
        match kind {
            b'L' => {
                self.gnu_longname = Some(trim_tar_str(payload));
            }
            b'K' => {
                self.gnu_longlink = Some(trim_tar_str(payload));
            }
            b'x' | b'g' => {
                let mut idx = 0usize;
                let n = payload.len();
                while idx < n {
                    let mut sp = idx;
                    while sp < n && payload[sp].is_ascii_digit() {
                        sp += 1;
                    }
                    if sp == idx || sp >= n || payload[sp] != b' ' {
                        break;
                    }
                    let rec_len: usize = match std::str::from_utf8(&payload[idx..sp])
                        .ok()
                        .and_then(|s| s.parse().ok())
                    {
                        Some(v) if v > (sp - idx + 1) && idx + v <= n => v,
                        _ => break,
                    };
                    let mut cend = idx + rec_len;
                    let cstart = sp + 1;
                    if cend > cstart && payload[cend - 1] == b'\n' {
                        cend -= 1;
                    }
                    if let Some(eq_rel) = payload[cstart..cend].iter().position(|&b| b == b'=') {
                        let eq = cstart + eq_rel;
                        let key = std::str::from_utf8(&payload[cstart..eq]).unwrap_or("");
                        let val = String::from_utf8_lossy(&payload[eq + 1..cend]).into_owned();
                        if kind == b'g' {
                            match key {
                                "path" => self.global_pax_path = Some(val),
                                "linkpath" => self.global_pax_linkpath = Some(val),
                                _ => {}
                            }
                        } else {
                            match key {
                                "path" => self.pax_path = Some(val),
                                "linkpath" => self.pax_linkpath = Some(val),
                                "size" => {
                                    if let Ok(sz) = val.trim().parse::<usize>() {
                                        self.pax_size = Some(sz);
                                    }
                                }
                                _ => {}
                            }
                        }
                    }
                    idx += rec_len;
                }
            }
            _ => {}
        }
        Ok(())
    }

    fn ensure_dir(&mut self, dir: &Path) -> Result<(), String> {
        if self.created_dirs.contains(dir) {
            return Ok(());
        }
        if let Ok(meta) = fs::symlink_metadata(dir) {
            if !meta.file_type().is_dir() {
                unlink_any(dir);
            }
        }
        fs::create_dir_all(dir)
            .map_err(|e| format!("cannot create dir {}: {}", dir.display(), e))?;
        self.created_dirs.insert(dir.to_path_buf());
        Ok(())
    }
}

fn unlink_any(path: &Path) {
    if let Ok(meta) = fs::symlink_metadata(path) {
        if meta.file_type().is_dir() && !meta.file_type().is_symlink() {
            let _ = fs::remove_dir_all(path);
        } else {
            let _ = fs::remove_file(path);
        }
    }
}

fn parse_tar_num(field: &[u8]) -> u64 {
    if field.is_empty() {
        return 0;
    }
    if (field[0] & 0x80) != 0 {
        let mut v: u64 = (field[0] & 0x7f) as u64;
        for &b in &field[1..] {
            v = (v << 8) | (b as u64);
        }
        return v;
    }
    let mut v: u64 = 0;
    for &b in field {
        if b == 0 {
            break;
        }
        if b == b' ' {
            continue;
        }
        if (b'0'..=b'7').contains(&b) {
            v = (v << 3) + ((b - b'0') as u64);
        }
    }
    v
}

fn read_c_field(field: &[u8]) -> String {
    let end = field.iter().position(|&b| b == 0).unwrap_or(field.len());
    String::from_utf8_lossy(&field[..end]).into_owned()
}

fn trim_tar_str(bytes: &[u8]) -> String {
    let mut end = bytes.len();
    while end > 0 && matches!(bytes[end - 1], 0 | b'\n' | b'\r') {
        end -= 1;
    }
    String::from_utf8_lossy(&bytes[..end]).into_owned()
}

fn sanitize_rel_path(
    raw: &str,
    created_symlinks: &HashSet<String>,
) -> Result<Option<String>, String> {
    if raw.contains('\0') {
        return Err("tar entry contains NUL byte".to_string());
    }
    let mut parts: Vec<&str> = Vec::new();
    for seg in raw.split('/') {
        match seg {
            "" | "." => continue,
            ".." => return Err(format!("tar path traversal rejected: {}", raw)),
            s => parts.push(s),
        }
    }
    if parts.is_empty() {
        return Ok(None);
    }
    // Reject writing through any previously extracted symlink directory
    if !created_symlinks.is_empty() && parts.len() > 1 {
        let mut prefix = String::new();
        for seg in &parts[..parts.len() - 1] {
            if !prefix.is_empty() {
                prefix.push('/');
            }
            prefix.push_str(seg);
            if created_symlinks.contains(&prefix) {
                return Err(format!("tar path traverses symlink {}: {}", prefix, raw));
            }
        }
    }
    Ok(Some(parts.join("/")))
}

fn validate_symlink_target(entry_rel: &str, target: &str) -> Result<(), String> {
    if target.starts_with('/') {
        if target.starts_with("/data/data/com.termux/")
            || target.starts_with("/data/data/com.ds.")
            || target.starts_with("/data/user/0/com.ds.")
            || target.starts_with("/system/")
        {
            return Ok(());
        }
        return Err(format!("disallowed absolute symlink target: {}", target));
    }
    let mut depth: isize = entry_rel.split('/').count().saturating_sub(1) as isize;
    for seg in target.split('/') {
        match seg {
            "" | "." => {}
            ".." => {
                depth -= 1;
                if depth < 0 {
                    return Err(format!(
                        "symlink target escapes archive root: {} -> {}",
                        entry_rel, target
                    ));
                }
            }
            _ => depth += 1,
        }
    }
    Ok(())
}

fn decompress_and_extract_tar_zst(
    zst_slice: &[u8],
    dest_dir: &Path,
    expected_sha256: &str,
) -> Result<(), String> {
    if zst_slice.len() < 4 || zst_slice[0..4] != [0x28, 0xB5, 0x2F, 0xFD] {
        return Err("archive does not start with Zstandard magic (0xFD2FB528)".to_string());
    }
    let trimmed_sha = expected_sha256.trim();
    if !trimmed_sha.is_empty() {
        let actual_sha = sha256_hex(zst_slice);
        if !actual_sha.eq_ignore_ascii_case(trimmed_sha) {
            return Err(format!(
                "archive SHA-256 mismatch (expected={}, actual={})",
                trimmed_sha, actual_sha
            ));
        }
    }

    let dctx = ZstdCtx::new()?;
    let mut extractor = TarExtractor::new(dest_dir.to_path_buf())?;

    let mut out_vec = vec![0u8; 512 * 1024];
    let mut in_buf = ZstdInBuffer {
        src: zst_slice.as_ptr() as *const c_void,
        size: zst_slice.len(),
        pos: 0,
    };

    loop {
        let mut out_buf = ZstdOutBuffer {
            dst: out_vec.as_mut_ptr() as *mut c_void,
            size: out_vec.len(),
            pos: 0,
        };
        let ret = unsafe { ZSTD_decompressStream(dctx.0, &mut out_buf, &mut in_buf) };
        if unsafe { ZSTD_isError(ret) } != 0 {
            let err_name = unsafe {
                let p = ZSTD_getErrorName(ret);
                if p.is_null() {
                    "unknown zstd error"
                } else {
                    CStr::from_ptr(p).to_str().unwrap_or("zstd error")
                }
            };
            return Err(format!("ZSTD_decompressStream failed: {}", err_name));
        }
        if out_buf.pos > 0 {
            extractor.feed(&out_vec[..out_buf.pos])?;
        }
        if ret == 0 && in_buf.pos >= in_buf.size {
            break;
        }
        if out_buf.pos == 0 && in_buf.pos >= in_buf.size {
            return Err("truncated Zstandard frame in archive".to_string());
        }
    }

    extractor.finish()
}

// ============================================================================
// Native Bionic Prefix, Shebang, Symlink & chmod Rewriter
// ============================================================================

fn fixup_prefix_impl(prefix_dir: &Path, pkg_name: &str) -> Result<i32, String> {
    if pkg_name.is_empty() || pkg_name.len() > 10 {
        return Err(format!("invalid package name length: {}", pkg_name));
    }
    let prefix_str = prefix_dir
        .to_str()
        .ok_or_else(|| "non-UTF8 prefix path".to_string())?;

    let mut padded_pkg = String::with_capacity(10);
    padded_pkg.push_str(pkg_name);
    while padded_pkg.len() < 10 {
        padded_pkg.push('/');
    }
    let elf_repl = format!("/data/data/{}/files/usr", padded_pkg);
    assert_eq!(elf_repl.len(), 31);
    let elf_repl_bytes = elf_repl.as_bytes();

    let mut count: i32 = 0;

    // 1. Ensure multicall symlink bin/agy-box -> ds-box exists without duplicating 4.3 MB ELF
    let ds_box = prefix_dir.join("bin/ds-box");
    let agy_box = prefix_dir.join("bin/agy-box");
    if ds_box.is_file() && fs::symlink_metadata(&agy_box).is_err() {
        let _ = std::os::unix::fs::symlink("ds-box", &agy_box);
    }

    // 2. Repoint absolute symlinks across the entire prefix
    count += fix_symlinks_recursive(prefix_dir, prefix_str);

    // 2. Rewrite ELF 31-byte prefixes in bin/, libexec/, lib/
    for elf_sub in ["bin", "libexec", "lib"] {
        let dir = prefix_dir.join(elf_sub);
        if dir.is_dir() {
            count += rewrite_elfs_recursive(&dir, pkg_name, elf_repl_bytes);
        }
    }

    // 3. Rewrite text scripts & shebangs in bin, libexec, etc, share, var
    for script_sub in ["bin", "libexec", "etc", "share", "var"] {
        let dir = prefix_dir.join(script_sub);
        if dir.is_dir() {
            let force_exec = script_sub == "bin" || script_sub == "libexec";
            count += rewrite_scripts_and_chmod(&dir, prefix_str.as_bytes(), force_exec);
        }
    }

    // 4. Remove Termux profile hooks that shouldn't run on DeepSeek Android
    for hook in [
        "etc/profile.d/01-termux-bootstrap-second-stage-fallback.sh",
        "etc/profile.d/init-termux-properties.sh",
    ] {
        let hp = prefix_dir.join(hook);
        if hp.exists() {
            let _ = fs::remove_file(&hp);
        }
    }

    Ok(count)
}

fn match_foreign_app_usr_prefix(bytes: &[u8]) -> Option<usize> {
    // Matches `/data/data/com.<pkg>/files/usr` or `/data/user/<digits>/com.<pkg>/files/usr`
    if bytes.starts_with(b"/data/data/com.") {
        let rest = &bytes[b"/data/data/".len()..];
        let slash = rest.iter().position(|&b| b == b'/')?;
        let pkg = &rest[..slash];
        if pkg.len() < 5 {
            return None;
        }
        // Handle padded `/data/data/com.agy////files/usr` too
        let mut after = slash;
        while after < rest.len() && rest[after] == b'/' {
            after += 1;
        }
        if rest[after..].starts_with(b"files/usr") {
            let end = b"/data/data/".len() + after + b"files/usr".len();
            if end == bytes.len() || !is_ident_cont(bytes[end]) {
                return Some(end);
            }
        }
        return None;
    }
    if bytes.starts_with(b"/data/user/") {
        let rest = &bytes[b"/data/user/".len()..];
        let mut d = 0;
        while d < rest.len() && rest[d].is_ascii_digit() {
            d += 1;
        }
        if d == 0 || d >= rest.len() || rest[d] != b'/' {
            return None;
        }
        let after_uid = &rest[d + 1..];
        if !after_uid.starts_with(b"com.") {
            return None;
        }
        let slash = after_uid.iter().position(|&b| b == b'/')?;
        let mut after = slash;
        while after < after_uid.len() && after_uid[after] == b'/' {
            after += 1;
        }
        if after_uid[after..].starts_with(b"files/usr") {
            let end = b"/data/user/".len() + d + 1 + after + b"files/usr".len();
            if end == bytes.len() || !is_ident_cont(bytes[end]) {
                return Some(end);
            }
        }
    }
    None
}

#[inline]
fn is_ident_cont(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_' || b == b'-' || b == b'.'
}

fn fix_symlinks_recursive(dir: &Path, new_prefix: &str) -> i32 {
    let entries = match fs::read_dir(dir) {
        Ok(rd) => rd,
        Err(_) => return 0,
    };
    let mut fixed = 0;
    for entry in entries.flatten() {
        let path = entry.path();
        let ft = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        if ft.is_symlink() {
            if let Ok(target) = fs::read_link(&path) {
                if let Some(tstr) = target.to_str() {
                    if let Some(plen) = match_foreign_app_usr_prefix(tstr.as_bytes()) {
                        let matched = &tstr[..plen];
                        if matched != new_prefix {
                            let suffix = &tstr[plen..];
                            if suffix.is_empty() || suffix.starts_with('/') {
                                let new_target = format!("{}{}", new_prefix, suffix);
                                let _ = fs::remove_file(&path);
                                if std::os::unix::fs::symlink(&new_target, &path).is_ok() {
                                    fixed += 1;
                                }
                            }
                        }
                    }
                }
            }
        } else if ft.is_dir() {
            fixed += fix_symlinks_recursive(&path, new_prefix);
        }
    }
    fixed
}

fn rewrite_elfs_recursive(dir: &Path, pkg_name: &str, elf_repl_bytes: &[u8]) -> i32 {
    let entries = match fs::read_dir(dir) {
        Ok(rd) => rd,
        Err(_) => return 0,
    };
    let mut modified_count = 0;
    for entry in entries.flatten() {
        let path = entry.path();
        let ft = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        if ft.is_symlink() {
            continue;
        }
        if ft.is_dir() {
            modified_count += rewrite_elfs_recursive(&path, pkg_name, elf_repl_bytes);
        } else if ft.is_file() {
            let fname = entry.file_name();
            let name_str = fname.to_string_lossy();
            if name_str == "libagy-go-dns.so"
                || name_str == "ds-box"
                || name_str == "agy-box"
                || name_str == "oc-box"
            {
                continue;
            }
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            let len = meta.len();
            if !(64..=16 * 1024 * 1024).contains(&len) {
                continue;
            }
            let mut hdr = [0u8; 4];
            if let Ok(mut f) = File::open(&path) {
                use std::io::Read;
                if f.read_exact(&mut hdr).is_err() || hdr != *b"\x7fELF" {
                    continue;
                }
            } else {
                continue;
            }
            let mut data = match fs::read(&path) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let needles: [&[u8]; 4] = [
                b"/data/data/com.termux/files/usr",
                b"/data/data/com.agy////files/usr",
                b"/data/data/com.oc.ide/files/usr",
                if pkg_name != "com.ds.ide" {
                    b"/data/data/com.ds.ide/files/usr"
                } else {
                    b""
                },
            ];
            let mut changed = false;
            for needle in needles {
                if needle.len() != 31 || needle == elf_repl_bytes {
                    continue;
                }
                let mut i = 0;
                while i + 31 <= data.len() {
                    if data[i] == b'/' && data[i + 11] == b'c' && &data[i..i + 31] == needle {
                        data[i..i + 31].copy_from_slice(elf_repl_bytes);
                        changed = true;
                        i += 31;
                    } else {
                        i += 1;
                    }
                }
            }
            if changed && write_atomic(&path, &data, 0o755) {
                modified_count += 1;
            }
        }
    }
    modified_count
}

fn rewrite_scripts_and_chmod(dir: &Path, new_prefix: &[u8], force_exec: bool) -> i32 {
    let entries = match fs::read_dir(dir) {
        Ok(rd) => rd,
        Err(_) => return 0,
    };
    let mut modified_count = 0;
    for entry in entries.flatten() {
        let path = entry.path();
        let ft = match entry.file_type() {
            Ok(t) => t,
            Err(_) => continue,
        };
        if ft.is_symlink() {
            continue;
        }
        if ft.is_dir() {
            modified_count += rewrite_scripts_and_chmod(&path, new_prefix, force_exec);
        } else if ft.is_file() {
            let meta = match entry.metadata() {
                Ok(m) => m,
                Err(_) => continue,
            };
            let orig_mode = meta.permissions().mode() & 0o777;
            if force_exec && (orig_mode & 0o111) == 0 {
                let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o755));
            }
            let len = meta.len();
            if !(15..=4 * 1024 * 1024).contains(&len) {
                continue;
            }
            let data = match fs::read(&path) {
                Ok(d) => d,
                Err(_) => continue,
            };
            let sample = &data[..data.len().min(8192)];
            if sample.starts_with(b"\x7fELF") || sample.contains(&0) {
                continue;
            }
            if !contains_subslice(&data, b"/data/") {
                continue;
            }
            let mut out = Vec::with_capacity(data.len() + 64);
            let mut i = 0;
            let mut changed = false;
            while i < data.len() {
                if data[i] == b'/' && i + 20 <= data.len() && data[i..].starts_with(b"/data/") {
                    if let Some(plen) = match_foreign_app_usr_prefix(&data[i..]) {
                        if &data[i..i + plen] != new_prefix {
                            out.extend_from_slice(new_prefix);
                            changed = true;
                        } else {
                            out.extend_from_slice(&data[i..i + plen]);
                        }
                        i += plen;
                        continue;
                    }
                }
                out.push(data[i]);
                i += 1;
            }
            if changed {
                let target_mode = if force_exec || (orig_mode & 0o111) != 0 {
                    0o755
                } else {
                    0o644
                };
                if write_atomic(&path, &out, target_mode) {
                    modified_count += 1;
                }
            }
        }
    }
    modified_count
}

fn contains_subslice(haystack: &[u8], needle: &[u8]) -> bool {
    if needle.is_empty() || haystack.len() < needle.len() {
        return false;
    }
    haystack.windows(needle.len()).any(|w| w == needle)
}

fn write_atomic(target: &Path, data: &[u8], mode: u32) -> bool {
    let parent = match target.parent() {
        Some(p) => p,
        None => return false,
    };
    let fname = match target.file_name() {
        Some(n) => n.to_string_lossy(),
        None => return false,
    };
    let tmp = parent.join(format!(".{}.rstmp", fname));
    let mut file = match OpenOptions::new()
        .write(true)
        .create(true)
        .truncate(true)
        .mode(mode)
        .open(&tmp)
    {
        Ok(f) => f,
        Err(_) => return false,
    };
    if file.write_all(data).is_err() {
        let _ = fs::remove_file(&tmp);
        return false;
    }
    unsafe {
        let _ = fchmod(file.as_raw_fd(), mode);
    }
    drop(file);
    if fs::rename(&tmp, target).is_err() {
        let _ = fs::remove_file(target);
        if fs::rename(&tmp, target).is_err() {
            let _ = fs::remove_file(&tmp);
            return false;
        }
    }
    true
}

fn write_err(err_buf: *mut c_char, err_cap: usize, msg: &str) {
    if err_buf.is_null() || err_cap == 0 {
        return;
    }
    let bytes = msg.as_bytes();
    let n = bytes.len().min(err_cap - 1);
    unsafe {
        ptr::copy_nonoverlapping(bytes.as_ptr(), err_buf as *mut u8, n);
        *(err_buf.add(n) as *mut u8) = 0;
    }
}

// ============================================================================
// Exported C FFI Entrypoints (called by jni_shim.c)
// ============================================================================

#[no_mangle]
pub unsafe extern "C" fn ds_rust_extract_apk_tar_zst(
    apk_path: *const c_char,
    entry_name: *const c_char,
    dest_dir: *const c_char,
    expected_sha256: *const c_char,
    err_buf: *mut c_char,
    err_cap: usize,
) -> c_int {
    let res = panic::catch_unwind(|| -> Result<(), String> {
        let apk_str = CStr::from_ptr(apk_path)
            .to_str()
            .map_err(|_| "invalid UTF-8 in apk_path".to_string())?;
        let entry_str = CStr::from_ptr(entry_name)
            .to_str()
            .map_err(|_| "invalid UTF-8 in entry_name".to_string())?;
        let dest_str = CStr::from_ptr(dest_dir)
            .to_str()
            .map_err(|_| "invalid UTF-8 in dest_dir".to_string())?;
        let sha_str = if expected_sha256.is_null() {
            ""
        } else {
            CStr::from_ptr(expected_sha256).to_str().unwrap_or("")
        };

        let mmap = MmapFile::open(Path::new(apk_str))?;
        let zst_slice = find_apk_stored_slice(mmap.as_slice(), entry_str)?;
        decompress_and_extract_tar_zst(zst_slice, Path::new(dest_str), sha_str)
    });

    match res {
        Ok(Ok(())) => 0,
        Ok(Err(msg)) => {
            write_err(err_buf, err_cap, &msg);
            -1
        }
        Err(_) => {
            write_err(err_buf, err_cap, "panic inside ds_rust_extract_apk_tar_zst");
            -2
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn ds_rust_extract_file_tar_zst(
    tar_zst_path: *const c_char,
    dest_dir: *const c_char,
    expected_sha256: *const c_char,
    err_buf: *mut c_char,
    err_cap: usize,
) -> c_int {
    let res = panic::catch_unwind(|| -> Result<(), String> {
        let tar_str = CStr::from_ptr(tar_zst_path)
            .to_str()
            .map_err(|_| "invalid UTF-8 in tar_zst_path".to_string())?;
        let dest_str = CStr::from_ptr(dest_dir)
            .to_str()
            .map_err(|_| "invalid UTF-8 in dest_dir".to_string())?;
        let sha_str = if expected_sha256.is_null() {
            ""
        } else {
            CStr::from_ptr(expected_sha256).to_str().unwrap_or("")
        };

        let mmap = MmapFile::open(Path::new(tar_str))?;
        decompress_and_extract_tar_zst(mmap.as_slice(), Path::new(dest_str), sha_str)
    });

    match res {
        Ok(Ok(())) => 0,
        Ok(Err(msg)) => {
            write_err(err_buf, err_cap, &msg);
            -1
        }
        Err(_) => {
            write_err(err_buf, err_cap, "panic inside ds_rust_extract_file_tar_zst");
            -2
        }
    }
}

#[no_mangle]
pub unsafe extern "C" fn ds_rust_fixup_prefix(
    prefix_dir: *const c_char,
    pkg_name: *const c_char,
) -> c_int {
    let res = panic::catch_unwind(|| -> Result<i32, String> {
        let prefix_str = CStr::from_ptr(prefix_dir)
            .to_str()
            .map_err(|_| "invalid UTF-8 in prefix_dir".to_string())?;
        let pkg_str = CStr::from_ptr(pkg_name)
            .to_str()
            .map_err(|_| "invalid UTF-8 in pkg_name".to_string())?;
        fixup_prefix_impl(Path::new(prefix_str), pkg_str)
    });

    match res {
        Ok(Ok(count)) => count as c_int,
        _ => -1,
    }
}

// ============================================================================
// Exported JNI Entrypoints (com.ds.ide.runtime.NativeBootstrap)
// ============================================================================

extern "C" {
    fn ds_jni_get_utf(env: *mut c_void, s: *mut c_void) -> *const c_char;
    fn ds_jni_release_utf(env: *mut c_void, s: *mut c_void, utf: *const c_char);
    fn ds_jni_new_utf(env: *mut c_void, utf: *const c_char) -> *mut c_void;
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ds_ide_runtime_NativeBootstrap_nativeExtractApkTarZst(
    env: *mut c_void,
    _cls: *mut c_void,
    j_apk_path: *mut c_void,
    j_entry_name: *mut c_void,
    j_dest_dir: *mut c_void,
    j_expected_sha256: *mut c_void,
) -> *mut c_void {
    if j_apk_path.is_null() || j_entry_name.is_null() || j_dest_dir.is_null() {
        return ds_jni_new_utf(env, b"null argument to nativeExtractApkTarZst\0".as_ptr() as *const c_char);
    }
    let apk_c = ds_jni_get_utf(env, j_apk_path);
    let entry_c = ds_jni_get_utf(env, j_entry_name);
    let dest_c = ds_jni_get_utf(env, j_dest_dir);
    let sha_c = if j_expected_sha256.is_null() {
        b"\0".as_ptr() as *const c_char
    } else {
        ds_jni_get_utf(env, j_expected_sha256)
    };

    let mut err_buf = [0 as c_char; 1024];
    let rc = if !apk_c.is_null() && !entry_c.is_null() && !dest_c.is_null() && !sha_c.is_null() {
        ds_rust_extract_apk_tar_zst(apk_c, entry_c, dest_c, sha_c, err_buf.as_mut_ptr(), err_buf.len())
    } else {
        write_err(err_buf.as_mut_ptr(), err_buf.len(), "JNI GetStringUTFChars returned null");
        -1
    };

    if !j_expected_sha256.is_null() && !sha_c.is_null() {
        ds_jni_release_utf(env, j_expected_sha256, sha_c);
    }
    if !dest_c.is_null() {
        ds_jni_release_utf(env, j_dest_dir, dest_c);
    }
    if !entry_c.is_null() {
        ds_jni_release_utf(env, j_entry_name, entry_c);
    }
    if !apk_c.is_null() {
        ds_jni_release_utf(env, j_apk_path, apk_c);
    }

    if rc == 0 {
        ptr::null_mut()
    } else if err_buf[0] != 0 {
        ds_jni_new_utf(env, err_buf.as_ptr())
    } else {
        ds_jni_new_utf(env, b"native tar.zst extraction failed\0".as_ptr() as *const c_char)
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ds_ide_runtime_NativeBootstrap_nativeExtractFileTarZst(
    env: *mut c_void,
    _cls: *mut c_void,
    j_tar_path: *mut c_void,
    j_dest_dir: *mut c_void,
    j_expected_sha256: *mut c_void,
) -> *mut c_void {
    if j_tar_path.is_null() || j_dest_dir.is_null() {
        return ds_jni_new_utf(env, b"null argument to nativeExtractFileTarZst\0".as_ptr() as *const c_char);
    }
    let tar_c = ds_jni_get_utf(env, j_tar_path);
    let dest_c = ds_jni_get_utf(env, j_dest_dir);
    let sha_c = if j_expected_sha256.is_null() {
        b"\0".as_ptr() as *const c_char
    } else {
        ds_jni_get_utf(env, j_expected_sha256)
    };

    let mut err_buf = [0 as c_char; 1024];
    let rc = if !tar_c.is_null() && !dest_c.is_null() && !sha_c.is_null() {
        ds_rust_extract_file_tar_zst(tar_c, dest_c, sha_c, err_buf.as_mut_ptr(), err_buf.len())
    } else {
        write_err(err_buf.as_mut_ptr(), err_buf.len(), "JNI GetStringUTFChars returned null");
        -1
    };

    if !j_expected_sha256.is_null() && !sha_c.is_null() {
        ds_jni_release_utf(env, j_expected_sha256, sha_c);
    }
    if !dest_c.is_null() {
        ds_jni_release_utf(env, j_dest_dir, dest_c);
    }
    if !tar_c.is_null() {
        ds_jni_release_utf(env, j_tar_path, tar_c);
    }

    if rc == 0 {
        ptr::null_mut()
    } else if err_buf[0] != 0 {
        ds_jni_new_utf(env, err_buf.as_ptr())
    } else {
        ds_jni_new_utf(env, b"native file tar.zst extraction failed\0".as_ptr() as *const c_char)
    }
}

#[no_mangle]
pub unsafe extern "C" fn Java_com_ds_ide_runtime_NativeBootstrap_nativeFixupPrefix(
    env: *mut c_void,
    _cls: *mut c_void,
    j_prefix_dir: *mut c_void,
    j_pkg_name: *mut c_void,
) -> c_int {
    if j_prefix_dir.is_null() || j_pkg_name.is_null() {
        return -1;
    }
    let prefix_c = ds_jni_get_utf(env, j_prefix_dir);
    let pkg_c = ds_jni_get_utf(env, j_pkg_name);
    let rc = if !prefix_c.is_null() && !pkg_c.is_null() {
        ds_rust_fixup_prefix(prefix_c, pkg_c)
    } else {
        -1
    };
    if !pkg_c.is_null() {
        ds_jni_release_utf(env, j_pkg_name, pkg_c);
    }
    if !prefix_c.is_null() {
        ds_jni_release_utf(env, j_prefix_dir, prefix_c);
    }
    rc
}
