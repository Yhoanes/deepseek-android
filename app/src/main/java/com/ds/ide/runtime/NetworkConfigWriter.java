package com.ds.ide.runtime;

import android.content.Context;
import android.util.Log;

import com.ds.ide.net.DnsCollector;
import com.ds.ide.util.AtomicFileWriter;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Materializes DNS configuration atomically into:
 *  1) getFilesDir()/etc/{resolv.conf,hosts}
 *  2) getFilesDir()/usr/etc/{resolv.conf,hosts,nsswitch.conf}
 *  3) getFilesDir()/usr/glibc/etc/{resolv.conf,hosts,nsswitch.conf}
 * so both Bionic libcares (Node) and patched glibc (Claude CLI) resolve DNS natively
 * from millisecond 0 before Phase 2 bootstrap extraction even finishes.
 */
public final class NetworkConfigWriter {

    private static final String TAG = "NetworkConfigWriter";

    private NetworkConfigWriter() {
    }

    public static void write(Context appContext) {
        Set<String> servers = new LinkedHashSet<>();
        for (String ip : DnsCollector.collectDnsServers(appContext)) {
            if (ip != null && !ip.trim().isEmpty()) {
                servers.add(ip.trim());
            }
        }
        servers.add("8.8.8.8");
        servers.add("1.1.1.1");

        StringBuilder resolv = new StringBuilder();
        for (String ip : servers) {
            resolv.append("nameserver ").append(ip).append('\n');
        }
        String resolvTxt = resolv.toString();
        String hostsTxt = "127.0.0.1 localhost\n::1 ip6-localhost\n";
        String nssTxt = "hosts: files dns\n";

        File filesDir = appContext.getFilesDir();
        writeEtcDir(new File(filesDir, "etc"), resolvTxt, hostsTxt, nssTxt);
        writeEtcDir(new File(filesDir, "usr/etc"), resolvTxt, hostsTxt, nssTxt);
        writeEtcDir(new File(filesDir, "usr/glibc/etc"), resolvTxt, hostsTxt, nssTxt);
    }

    private static void writeEtcDir(File dir, String resolvTxt, String hostsTxt, String nssTxt) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "cannot create " + dir);
                return;
            }
            AtomicFileWriter.writeText(new File(dir, "resolv.conf"), resolvTxt);
            AtomicFileWriter.writeText(new File(dir, "hosts"), hostsTxt);
            AtomicFileWriter.writeText(new File(dir, "nsswitch.conf"), nssTxt);
        } catch (Exception e) {
            Log.w(TAG, "DNS config write warning for " + dir + ": " + e.getMessage());
        }
    }
}
