package adserve.sim;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;

/** The machine block every result line carries. */
public final class Machine {
    private Machine() {}

    public static void describe(ObjectNode n) {
        ObjectNode m = n.putObject("machine");
        String cpu = sysctl("machdep.cpu.brand_string");
        m.put("cpu", cpu.startsWith("sysctl") || cpu.isEmpty() ? otherCpu() : cpu);
        m.put("host", hostName());
        m.put("cores", Runtime.getRuntime().availableProcessors());
        String mem = sysctl("hw.memsize");
        m.put("memory_gb", mem.matches("\\d+") ? Long.parseLong(mem) / (1L << 30)
                : ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                        .getTotalMemorySize() / (1L << 30));
        m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        m.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        m.put("jvm_args", String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        m.put("load_avg_1m_at_start", loadAvg());
        m.put("low_power_mode", pmset("lowpowermode"));
        m.put("power_source", firstLine("pmset", "-g", "batt"));
    }

    public static String loadAvg() {
        String s = sysctl("vm.loadavg");
        if (s.isEmpty() || s.startsWith("sysctl")) {
            double la = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            if (la < 0) {
                // Windows has no load average: report CPU utilisation instead, labelled as such.
                double cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                        .getCpuLoad();
                return String.format("cpu_util %.2f", cpu);
            }
            return String.format("%.2f", la);
        }
        return s;
    }

    static String hostName() {
        String h = System.getenv("COMPUTERNAME");
        if (h != null && !h.isEmpty()) return h;
        return firstLine("hostname");
    }

    /** Linux from /proc/cpuinfo, Windows from the registry's processor name. */
    static String otherCpu() {
        if (System.getProperty("os.name").startsWith("Windows")) {
            for (String l : lines("reg", "query", "HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0",
                    "/v", "ProcessorNameString")) {
                int i = l.indexOf("REG_SZ");
                if (i > 0) return l.substring(i + 6).trim();
            }
            return System.getProperty("os.arch");
        }
        return linuxCpu();
    }

    static String linuxCpu() {
        for (String l : lines("sh", "-c", "grep -m1 'model name' /proc/cpuinfo")) {
            int i = l.indexOf(':');
            if (i > 0) return l.substring(i + 1).trim();
        }
        return System.getProperty("os.arch");
    }

    static String pmset(String key) {
        for (String line : lines("pmset", "-g")) {
            String t = line.trim();
            if (t.startsWith(key)) return t.substring(key.length()).trim();
        }
        return "";
    }

    static String firstLine(String... cmd) {
        java.util.List<String> l = lines(cmd);
        return l.isEmpty() ? "" : l.get(0).trim();
    }

    static java.util.List<String> lines(String... cmd) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s;
                while ((s = r.readLine()) != null) out.add(s);
            }
        } catch (Exception e) {
            // not macOS or not available
        }
        return out;
    }

    static String sysctl(String key) {
        try {
            Process p = new ProcessBuilder("sysctl", "-n", key).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String s = r.readLine();
                return s == null ? "" : s.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }
}
