import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.core.GuestEntity;
import org.cloudbus.cloudsim.core.HostEntity;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Random Task Scheduling pada CloudSim 7.0.1
 * Datacenter: DC_Surabaya (3 host, 10 VM heterogen) sesuai Tugas 3
 * Skenario: 100, 200, 500 cloudlet (Bag-of-Tasks)
 *
 * [DIUBAH] Setiap skenario dijalankan 10 kali (seed penjadwalan 1..10),
 * lalu dilaporkan rata-rata dan simpangan baku (sampel, n-1) tiap metrik.
 */
public class RandomSchedulingSim {

    static final long SEED = 42;                    // seed workload (tetap, SEED + n)
    // [DIUBAH] Skenario diperluas: 100, 200, 300, 400, 500 agar pola terlihat
    static final int[] SCENARIOS = {100, 200, 300, 400, 500};

    // [DIUBAH] Jumlah pengulangan dan nama metrik
    static final int RUNS = 10;
    static final String[] METRICS = {
            "Makespan", "AvgResponse", "Throughput", "AvgUtil", "LBI", "TotalCost", "Failed"};

    // Pemetaan VM -> Host sesuai hierarki topologi di Tugas 3
    static final int[] VM_TO_HOST = {0, 0, 0, 1, 1, 1, 2, 2, 2, 2};

    // ============ Custom allocation policy: VM ditempatkan sesuai topologi ============
    // Di CloudSim 7.x, Datacenter memanggil allocateHostForGuest -> findHostForGuest
    static class TopologyVmAllocationPolicy extends VmAllocationPolicySimple {
        public TopologyVmAllocationPolicy(List<? extends HostEntity> list) {
            super(list);
        }

        @Override
        public HostEntity findHostForGuest(GuestEntity guest) {
            List<HostEntity> hosts = getHostList();
            return hosts.get(VM_TO_HOST[guest.getId()]);
        }
    }

    // ============ Datacenter ============
    static Datacenter createDatacenter(String name) throws Exception {
        List<Host> hostList = new ArrayList<>();

        // Host 0: Compute-Optimized  (8 core x 4000 MIPS, 32GB, 2TB, 10Gbps)
        hostList.add(createHost(0, 4000, 32768, 2000000, 10000));
        // Host 1: General Purpose    (8 core x 2500 MIPS, 32GB, 1TB, 10Gbps)
        hostList.add(createHost(1, 2500, 32768, 1000000, 10000));
        // Host 2: Balanced-Economy   (8 core x 1500 MIPS, 16GB, 1TB, 2Gbps)
        // Catatan: spesifikasi awal 1 Gbps tidak cukup untuk 4 VM Small (4 x 500 = 2000 Mbps),
        // sehingga dinaikkan menjadi 2 Gbps agar semua VM terbentuk.
        hostList.add(createHost(2, 1500, 16384, 1000000, 2000));

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList,
                7.0,    // time zone
                0.03,   // cost per second (processing)
                0.05,   // cost per MB memory
                0.001,  // cost per MB storage
                0.01);  // cost per MB bandwidth

        return new Datacenter(name, characteristics,
                new TopologyVmAllocationPolicy(hostList),
                new LinkedList<Storage>(), 0);
    }

    static Host createHost(int id, double mipsPerCore, int ram, long storage, long bw) {
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            peList.add(new Pe(i, new PeProvisionerSimple(mipsPerCore)));
        }
        return new Host(id, new RamProvisionerSimple(ram),
                new BwProvisionerSimple(bw), storage, peList,
                new VmSchedulerTimeShared(peList));
    }

    // ============ VM (10 unit: 2 Large, 3 Medium, 5 Small) ============
    static List<Vm> createVms(int userId) {
        List<Vm> vms = new ArrayList<>();
        long imageSize = 10000;
        for (int i = 0; i < 10; i++) {
            double mips; int pes, ram; long bw;
            if (i < 2) {            // Large
                mips = 3000; pes = 2; ram = 8192; bw = 2500;
            } else if (i < 5) {     // Medium
                mips = 1800; pes = 2; ram = 4096; bw = 1000;
            } else {                // Small
                mips = 800;  pes = 1; ram = 2048; bw = 500;
            }
            vms.add(new Vm(i, userId, mips, pes, ram, bw, imageSize, "Xen",
                    new CloudletSchedulerTimeShared()));
        }
        return vms;
    }

    // ============ Workload generator (TIDAK DIUBAH) ============
    static List<Cloudlet> createCloudlets(int userId, int n, long seed) {
        Random rnd = new Random(seed);
        List<Cloudlet> list = new ArrayList<>();
        UtilizationModel full = new UtilizationModelFull();
        for (int i = 0; i < n; i++) {
            long length = 1000 + (long) (rnd.nextDouble() * 49000); // 1.000 - 50.000 MI
            long input = 50 + rnd.nextInt(251);                     // 50 - 300 MB
            long output = 20 + rnd.nextInt(81);                     // 20 - 100 MB
            Cloudlet c = new Cloudlet(i, length, 1, input, output, full, full, full);
            c.setUserId(userId);
            list.add(c);
        }
        return list;
    }

    // ============ ALGORITMA: RANDOM SCHEDULING ============
    static void randomSchedule(DatacenterBroker broker, List<Cloudlet> cloudlets,
                               List<Vm> vms, long seed) {
        Random rnd = new Random(seed);
        for (Cloudlet c : cloudlets) {
            Vm vm = vms.get(rnd.nextInt(vms.size())); // pilih VM acak
            broker.bindCloudletToVm(c.getCloudletId(), vm.getId());
        }
    }

    // ============ [DIUBAH] Satu kali run simulasi ============
    // Setiap run membuat CloudSim, datacenter, broker, VM, dan cloudlet BARU
    // (objek tidak dipakai ulang). Seed penjadwalan = nomor run; seed workload tetap.
    static double[] runOnce(int n, int run, boolean printDetail) throws Exception {
        Log.disable(); // matikan log CloudSim agar output bersih
        CloudSim.init(1, Calendar.getInstance(), false);

        createDatacenter("DC_Surabaya");
        DatacenterBroker broker = new DatacenterBroker("Broker");
        int userId = broker.getId();

        List<Vm> vms = createVms(userId);
        List<Cloudlet> cloudlets = createCloudlets(userId, n, SEED + n); // workload identik

        // [DIUBAH] Simpan dataset hasil sintesis sebagai bukti (cukup sekali, di run ke-1)
        if (run == 1) saveDataset(n, cloudlets);

        broker.submitGuestList(vms);
        broker.submitCloudletList(cloudlets);

        randomSchedule(broker, cloudlets, vms, run); // seed = run (1..10)

        CloudSim.startSimulation();
        CloudSim.stopSimulation();

        List<Cloudlet> done = broker.getCloudletReceivedList();
        return analyze(n, run, done, vms, printDetail);
    }

    // ============ Main ============
    public static void main(String[] args) throws Exception {
        List<String> perRunCsv = new ArrayList<>();
        perRunCsv.add("Cloudlets,Run,Seed," + String.join(",", METRICS));

        // [DIUBAH] Header ringkasan: mean dan std tiap metrik
        StringBuilder sh = new StringBuilder("Cloudlets");
        for (String m : METRICS) sh.append(",").append(m).append("_mean,").append(m).append("_std");
        List<String> summaryCsv = new ArrayList<>();
        summaryCsv.add(sh.toString());

        List<Integer> semuaN = new ArrayList<>();
        List<double[][]> semuaHasil = new ArrayList<>();

        for (int n : SCENARIOS) {
            double[][] results = new double[RUNS][];

            for (int run = 1; run <= RUNS; run++) {
                // Detail per VM hanya dicetak untuk run ke-1
                results[run - 1] = runOnce(n, run, run == 1);
                System.out.println("Skenario " + n + " cloudlet - run " + run + "/" + RUNS + " selesai");

                StringBuilder row = new StringBuilder();
                row.append(n).append(",").append(run).append(",").append(run);
                for (int k = 0; k < METRICS.length; k++) {
                    row.append(",").append(fmtRun(k, results[run - 1][k]));
                }
                perRunCsv.add(row.toString());
            }

            semuaN.add(n);
            semuaHasil.add(results);

            // Ringkasan: rata-rata dan simpangan baku (sampel, n-1) per metrik
            StringBuilder sr = new StringBuilder();
            sr.append(n);
            for (int k = 0; k < METRICS.length; k++) {
                double[] col = new double[RUNS];
                for (int r = 0; r < RUNS; r++) col[r] = results[r][k];
                sr.append(",").append(fmtStat(k, mean(col)))
                  .append(",").append(fmtStat(k, stdSample(col)));
            }
            summaryCsv.add(sr.toString());
        }

        System.out.println("\n===== HASIL PER RUN =====");
        for (String s : perRunCsv) System.out.println(s);
        System.out.println("\n===== RINGKASAN (mean dan std, n=" + RUNS + ") =====");
        for (String s : summaryCsv) System.out.println(s);

        // [DIUBAH] Tabel rata-rata (mean ± std) dari seluruh run untuk tiap jumlah cloudlet
        System.out.println("\n===== RATA-RATA " + RUNS + " RUN PER JUMLAH CLOUDLET (mean ± std) =====");
        printTabelRataRata(semuaN, semuaHasil);

        // [DIUBAH] Ringkasan dataset sintetis (bukti pembangkitan data)
        List<String> dsLines = new ArrayList<>();
        dsLines.add("Cloudlets,SEED_dasar,SeedWorkload,JumlahData,TotalMI,MinMI,MaxMI,MeanMI,MeanInput_MB,MeanOutput_MB");
        dsLines.addAll(datasetRingkasan);
        System.out.println("\n===== RINGKASAN DATASET SINTETIS =====");
        for (String s : dsLines) System.out.println(s);
        saveCsv("dataset_ringkasan.csv", dsLines);

        // [DIUBAH] Simpan ke file CSV di folder kerja (tempat mvn dijalankan)
        saveCsv("hasil_per_run.csv", perRunCsv);
        saveCsv("hasil_ringkasan.csv", summaryCsv);
    }

    // ============ [DIUBAH] Simpan dataset sintetis sebagai bukti ============
    // Menghasilkan dataset_<n>cloudlet.csv (isi tiap cloudlet) dan menambah satu baris
    // ke datasetRingkasan (statistik dataset) yang disimpan di akhir program.
    static final List<String> datasetRingkasan = new ArrayList<>();

    static void saveDataset(int n, List<Cloudlet> cloudlets) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("CloudletId,Length_MI,InputSize_MB,OutputSize_MB,PEs");
        double sumLen = 0, sumIn = 0, sumOut = 0;
        long minLen = Long.MAX_VALUE, maxLen = 0;
        for (Cloudlet c : cloudlets) {
            lines.add(c.getCloudletId() + "," + c.getCloudletLength() + ","
                    + c.getCloudletFileSize() + "," + c.getCloudletOutputSize() + ","
                    + c.getNumberOfPes());
            sumLen += c.getCloudletLength();
            sumIn += c.getCloudletFileSize();
            sumOut += c.getCloudletOutputSize();
            minLen = Math.min(minLen, c.getCloudletLength());
            maxLen = Math.max(maxLen, c.getCloudletLength());
        }
        saveCsv("dataset_" + n + "cloudlet.csv", lines);

        datasetRingkasan.add(n + "," + SEED + "," + (SEED + n) + "," + cloudlets.size() + ","
                + String.format(Locale.US, "%.0f,%d,%d,%.2f,%.2f,%.2f",
                        sumLen, minLen, maxLen, sumLen / n, sumIn / n, sumOut / n));
    }

    // ============ [DIUBAH] Tampilkan rata-rata semua run per jumlah cloudlet ============
    static void printTabelRataRata(List<Integer> ns, List<double[][]> all) {
        System.out.println(String.format(Locale.US, "%-10s %-20s %-20s %-18s %-16s %-16s %-14s %-8s",
                "Cloudlets", "Makespan(s)", "AvgResponse(s)", "Throughput(t/s)",
                "AvgUtil(%)", "LBI", "Cost($)", "Failed"));
        for (int i = 0; i < ns.size(); i++) {
            double[][] res = all.get(i);
            StringBuilder sb = new StringBuilder(String.format(Locale.US, "%-10d", ns.get(i)));
            int[] width = {20, 20, 18, 16, 16, 14, 8};
            for (int k = 0; k < METRICS.length; k++) {
                double[] col = new double[RUNS];
                for (int r = 0; r < RUNS; r++) col[r] = res[r][k];
                String cell = fmtStat(k, mean(col)) + " ± " + fmtStat(k, stdSample(col));
                sb.append(String.format("%-" + width[k] + "s ", cell));
            }
            System.out.println(sb);
        }
    }

    // ============ [DIUBAH] Statistik ============
    static double mean(double[] x) {
        double s = 0;
        for (double v : x) s += v;
        return s / x.length;
    }

    // Simpangan baku sampel (pembagi n-1)
    static double stdSample(double[] x) {
        if (x.length < 2) return 0;
        double m = mean(x), s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / (x.length - 1));
    }

    // ============ [DIUBAH] Format angka (Locale.US agar desimal selalu titik, aman untuk CSV) ============
    static String fmtRun(int metricIdx, double v) {
        if (METRICS[metricIdx].equals("Failed")) return String.format(Locale.US, "%d", Math.round(v));
        if (METRICS[metricIdx].equals("LBI")) return String.format(Locale.US, "%.4f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    static String fmtStat(int metricIdx, double v) {
        if (METRICS[metricIdx].equals("LBI")) return String.format(Locale.US, "%.4f", v);
        return String.format(Locale.US, "%.2f", v);
    }

    static void saveCsv(String file, List<String> lines) throws IOException {
        Files.write(Paths.get(file), lines, StandardCharsets.UTF_8);
        System.out.println("Tersimpan: " + Paths.get(file).toAbsolutePath());
    }

    // ============ Metrik (rumus TIDAK DIUBAH) ============
    // [DIUBAH] hanya bentuk keluaran: sekarang mengembalikan double[] sesuai urutan METRICS,
    // dan detail per VM dicetak hanya jika printDetail = true.
    static double[] analyze(int n, int run, List<Cloudlet> done, List<Vm> vms, boolean printDetail) {
        double minStart = Double.MAX_VALUE, maxFinish = 0, sumResp = 0, cost = 0;
        int success = 0;
        double[] busy = new double[vms.size()];
        int[] count = new int[vms.size()];
        long[] totalMi = new long[vms.size()];

        for (Cloudlet c : done) {
            if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                success++;
                minStart = Math.min(minStart, c.getExecStartTime());
                maxFinish = Math.max(maxFinish, c.getFinishTime());
                sumResp += c.getFinishTime() - c.getSubmissionTime();
                cost += c.getProcessingCost();
                Vm vm = vms.get(c.getVmId());
                // estimasi waktu sibuk VM = panjang task / total MIPS VM
                busy[c.getVmId()] += c.getCloudletLength() / (vm.getMips() * vm.getNumberOfPes());
                count[c.getVmId()]++;
                totalMi[c.getVmId()] += c.getCloudletLength();
            }
        }

        double makespan = maxFinish - minStart;
        double avgResp = success > 0 ? sumResp / success : 0;
        double throughput = makespan > 0 ? success / makespan : 0;
        int failed = n - success;

        // Utilisasi tiap VM = busy / makespan (maks 100%)
        double[] util = new double[vms.size()];
        double meanU = 0;
        for (int i = 0; i < util.length; i++) {
            util[i] = Math.min(1.0, busy[i] / makespan);
            meanU += util[i];
        }
        meanU /= util.length;

        double var = 0;
        for (double u : util) var += (u - meanU) * (u - meanU);
        double lbi = Math.sqrt(var / util.length);

        // [DIUBAH] Detail per VM hanya untuk run ke-1
        if (printDetail) {
            System.out.println("\n--- Skenario " + n + " cloudlet (detail run " + run + ", seed " + run + ") ---");
            System.out.println("Makespan        : " + fmtRun(0, makespan) + " s");
            System.out.println("Avg Response    : " + fmtRun(1, avgResp) + " s");
            System.out.println("Throughput      : " + fmtRun(2, throughput) + " task/s");
            System.out.println("Avg VM Util     : " + fmtRun(3, meanU * 100) + " %");
            System.out.println("LBI             : " + fmtRun(4, lbi));
            System.out.println("Total Cost      : $" + fmtRun(5, cost));
            System.out.println("Failed Cloudlet : " + failed);
            System.out.println("\nDetail per VM:");
            System.out.println(String.format(Locale.US, "%-4s %-7s %-5s %-5s %-8s %-6s %-6s %-9s %-11s %-8s",
                    "VM", "Tipe", "Host", "vCPU", "MIPS/CPU", "RAM", "BW", "Cloudlet", "Total MI", "Util(%)"));
            for (int i = 0; i < vms.size(); i++) {
                Vm vm = vms.get(i);
                String tipe = i < 2 ? "Large" : (i < 5 ? "Medium" : "Small");
                System.out.println(String.format(Locale.US, "%-4d %-7s %-5d %-5d %-8.0f %-6d %-6d %-9d %-11d %-8s",
                        i, tipe, VM_TO_HOST[i] + 1, vm.getNumberOfPes(), vm.getMips(),
                        vm.getRam(), vm.getBw(), count[i], totalMi[i], fmtRun(3, util[i] * 100)));
            }
            System.out.println();
        }

        // Urutan sesuai METRICS: Makespan, AvgResponse, Throughput, AvgUtil, LBI, TotalCost, Failed
        return new double[]{makespan, avgResp, throughput, meanU * 100, lbi, cost, failed};
    }
}
