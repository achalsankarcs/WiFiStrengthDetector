from flask import Flask, request, send_file
import matplotlib
matplotlib.use('Agg')  # Prevent GUI error in non-main thread

import matplotlib.pyplot as plt
import seaborn as sns
import numpy as np
import io
import json
from scipy.interpolate import griddata
from matplotlib.colors import LinearSegmentedColormap

app = Flask(__name__)

@app.route("/heatmap", methods=["POST"])
def generate_heatmap():
    try:
        data = request.get_json()
        if not data or len(data) < 2:
            return "At least 2 points required", 400

        x = [point["x"] for point in data]
        y = [point["y"] for point in data]
        rssi = [point["rssi"] for point in data]

        # Interpolation grid
        xi = np.linspace(min(x) - 1, max(x) + 1, 200)
        yi = np.linspace(min(y) - 1, max(y) + 1, 200)
        xi, yi = np.meshgrid(xi, yi)

        # Interpolate RSSI
        zi = griddata((x, y), rssi, (xi, yi), method='cubic')

        # Define custom RGB colormap: red → green → blue
        rgb_cmap = LinearSegmentedColormap.from_list(
            "rgb_gradient",
            [(0.0, "blue"), (0.5, "green"), (1.0, "red")],
            N=256
        )

        # Plot setup
        plt.figure(figsize=(7, 6))
        contour = plt.contourf(xi, yi, zi, levels=100, cmap=rgb_cmap)

        # Colorbar
        cbar = plt.colorbar(contour)
        cbar.set_label("RSSI (dBm)", fontsize=12)

        # Plot data points
        for i in range(len(x)):
            plt.scatter(x[i], y[i], color='black', s=20, marker='x')

        # Router marker at (0, 0)
        plt.scatter(0, 0, color='black', s=120, marker='X', label="Router")
        plt.legend()

        plt.title("WiFi Signal Heatmap", fontsize=14, weight='bold')
        plt.xlabel("X Coordinate")
        plt.ylabel("Y Coordinate")
        plt.axis('equal')
        plt.tight_layout()

        # Save to in-memory buffer
        buf = io.BytesIO()
        plt.savefig(buf, format='png')
        buf.seek(0)
        plt.close()

        return send_file(buf, mimetype='image/png')

    except Exception as e:
        print("Error generating heatmap:", str(e))
        return "Internal Server Error", 500

if __name__ == "__main__":
    app.run(host='0.0.0.0', port=5000)