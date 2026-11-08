"""
Membaca hasil_ringkasan.csv (keluaran RandomSchedulingSim.java) lalu membuat grafik:
  1. Makespan
  2. Throughput
  3. Resource Utilization (rata-rata utilisasi VM)
  4. Total Cost
Tiap grafik menampilkan rata-rata (garis) dan simpangan baku (error bar) dari 10 run.

Cara pakai (jalankan di folder yang sama dengan file CSV):
    python plot_hasil.py                 -> simpan PNG saja
    python plot_hasil.py --show          -> simpan PNG dan tampilkan jendela grafik
    python plot_hasil.py path\\ke\\hasil_ringkasan.csv
Dependency: pip install matplotlib
"""
import csv
import sys
from pathlib import Path

import matplotlib
if "--show" not in sys.argv:
    matplotlib.use("Agg")  # tanpa jendela, cukup simpan file
import matplotlib.pyplot as plt

# (kolom CSV, judul, label sumbu y, nama file)
GRAFIK = [
    ("Makespan",    "Makespan vs Jumlah Cloudlet",             "Makespan (detik)",         "grafik_makespan.png"),
    ("Throughput",  "Throughput vs Jumlah Cloudlet",           "Throughput (task/detik)",  "grafik_throughput.png"),
    ("AvgUtil",     "Resource Utilization vs Jumlah Cloudlet", "Rata-rata utilisasi VM (%)", "grafik_utilisasi.png"),
    ("TotalCost",   "Total Cost vs Jumlah Cloudlet",           "Total biaya ($)",          "grafik_total_cost.png"),
]


def baca_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        baris = list(csv.DictReader(f))
    if not baris:
        sys.exit(f"File kosong: {path}")
    data = {"Cloudlets": [int(r["Cloudlets"]) for r in baris]}
    for kolom, *_ in GRAFIK:
        data[kolom + "_mean"] = [float(r[kolom + "_mean"]) for r in baris]
        data[kolom + "_std"] = [float(r[kolom + "_std"]) for r in baris]
    return data


def gambar(ax, data, kolom, judul, ylabel):
    x = data["Cloudlets"]
    ax.errorbar(x, data[kolom + "_mean"], yerr=data[kolom + "_std"],
                marker="o", capsize=4, linewidth=2, color="#1f77b4")
    ax.set_title(judul, fontsize=11, fontweight="bold")
    ax.set_xlabel("Jumlah cloudlet")
    ax.set_ylabel(ylabel)
    ax.set_xticks(x)
    ax.grid(True, linestyle="--", alpha=0.4)
    # label nilai rata-rata di atas tiap titik
    for xi, yi in zip(x, data[kolom + "_mean"]):
        ax.annotate(f"{yi:.2f}", (xi, yi), textcoords="offset points",
                    xytext=(0, 8), ha="center", fontsize=8)


def main():
    argumen = [a for a in sys.argv[1:] if not a.startswith("--")]
    csv_path = Path(argumen[0]) if argumen else Path("hasil_ringkasan.csv")
    if not csv_path.exists():
        sys.exit(f"File tidak ditemukan: {csv_path.resolve()}\n"
                 "Jalankan program Java dulu, atau beri path CSV sebagai argumen.")

    data = baca_csv(csv_path)
    folder = csv_path.parent

    # Grafik individual
    for kolom, judul, ylabel, nama in GRAFIK:
        fig, ax = plt.subplots(figsize=(7, 4.5))
        gambar(ax, data, kolom, judul, ylabel)
        fig.tight_layout()
        fig.savefig(folder / nama, dpi=150)
        print("Tersimpan:", (folder / nama).resolve())
        if "--show" not in sys.argv:
            plt.close(fig)

    # Grafik gabungan 2x2 (praktis untuk slide)
    fig, axes = plt.subplots(2, 2, figsize=(12, 8))
    for ax, (kolom, judul, ylabel, _) in zip(axes.flat, GRAFIK):
        gambar(ax, data, kolom, judul, ylabel)
    fig.suptitle("Random Scheduling - Rata-rata 10 Run (error bar = simpangan baku)",
                 fontsize=13, fontweight="bold")
    fig.tight_layout(rect=[0, 0, 1, 0.96])
    gabungan = folder / "grafik_gabungan.png"
    fig.savefig(gabungan, dpi=150)
    print("Tersimpan:", gabungan.resolve())

    if "--show" in sys.argv:
        plt.show()


if __name__ == "__main__":
    main()
