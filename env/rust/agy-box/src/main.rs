mod daemon;
mod deb;
mod env_util;
mod fix_paths;
mod notify;
mod pkg;
mod shim;
mod vercmp;

use std::env;
use std::path::Path;
use std::process;

fn print_box_usage() {
    println!(
        "ds-box 0.3.1 -- Native Rust multicall toolkit for DeepSeek Android\n\n\
         Applets:\n  \
           ds-box pkg <args...>         Fast parallel package manager (apt/pkg)\n  \
           ds-box daemon <args...>      Zero-Python background micro-daemon\n  \
           ds-box fix-paths [paths...]  Parallel SIMD Termux prefix rewriter\n  \
           ds-box notify <args...>      Android system notification bridge\n  \
           ds-box sync-bridges          Materialize runtime/bin tool shims\n  \
           ds-box shim <tool> [args...] Direct execve bionic environment shim\n  \
           ds-box --probe               Exit 0 immediately (capability probe)"
    );
}

fn dispatch_applet(applet: &str, args: &[String]) -> i32 {
    #[cfg(unix)]
    if applet != "shim" {
        shim::setup_bionic_env(&env_util::resolve_prefix());
    }
    match applet {
        "pkg" | "apt" | "apt-get" | "apt-cache" => pkg::run(args),
        "daemon" | "ds-daemon" | "cc-daemon" | "agy-daemon" => daemon::run(args),
        "fix-paths" | "ds-fix-paths" | "cc-fix-paths" | "agy-fix-paths" => fix_paths::run(args),
        "notify" | "ds-notify" | "cc-notify" | "agy-notify" => notify::run(args),
        "sync-bridges" => {
            let prefix = env_util::resolve_prefix();
            env_util::sync_runtime_bridges(&prefix);
            0
        }
        "shim" => {
            if let Some((tool, rest)) = args.split_first() {
                shim::exec_named(tool, rest)
            } else {
                1
            }
        }
        other => shim::exec_named(other, args),
    }
}

fn main() {
    let raw_args: Vec<String> = env::args().collect();
    let argv0 = raw_args.first().map(|s| s.as_str()).unwrap_or("ds-box");
    let invoked_name = Path::new(argv0)
        .file_name()
        .and_then(|s| s.to_str())
        .unwrap_or("ds-box");
    let stem = invoked_name.strip_suffix(".exe").unwrap_or(invoked_name);

    let code = if matches!(stem, "ds-box" | "agy-box" | "oc-box" | "cc-box") {
        match raw_args.get(1).map(|s| s.as_str()) {
            None | Some("-h") | Some("--help") | Some("help") => {
                print_box_usage();
                0
            }
            Some("--probe") => 0,
            Some("-V") | Some("--version") | Some("version") => {
                println!("ds-box 0.3.1 (rust)");
                0
            }
            Some(sub) => dispatch_applet(sub, &raw_args[2..]),
        }
    } else {
        dispatch_applet(stem, &raw_args[1..])
    };

    process::exit(code);
}
