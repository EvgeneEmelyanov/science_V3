package simcore.io;

import simcore.Main;
import simcore.config.SimulationConstants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * Загрузка и обработка входных данных: нагрузка и скорость ветра.
 */
public class InputDataLoader {

    public InputData load(String loadFilePath, String windFilePath) throws IOException {

        double[] loadKw = new double[SimulationConstants.DATA_SIZE];
        double[] windMs = new double[SimulationConstants.DATA_SIZE];

        loadColumnInto(loadFilePath, loadKw);
        loadColumnInto(windFilePath, windMs);

        scaleLoad(loadKw);
        scaleWind(windMs);

        return new InputData(loadKw, windMs);
    }

    private void loadColumnInto(String filePath, double[] target) throws IOException {

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            int i = 0;

            while ((line = br.readLine()) != null && i < target.length) {
                line = line.trim();
                if (!line.isEmpty()) {
                    target[i++] = Double.parseDouble(line.replace(",", "."));
                }
            }

            if (i != target.length) {
                throw new IOException("Ожидалось " + target.length +
                        " строк, получено " + i + " (" + filePath + ")");
            }
        }
    }

    private void scaleLoad(double[] arr) {
//        double factor = SimulationConstants.MAX_LOAD;
        double factor = Main.MAX_LOAD;
        for (int i = 0; i < arr.length; i++) {
            arr[i] *= factor;
        }
    }

    private void scaleWind(double[] arr) {

        double z0 = SimulationConstants.Z_FACTOR;
        double hRef = SimulationConstants.WIND_REFERENCE_HEIGHT_M;
        double hMast = SimulationConstants.MAST_HEIGHT_M;

        double rawFactor = Math.log(hMast / z0) / Math.log(hRef / z0);
        double factor = Math.round(rawFactor * 1000.0) / 1000.0;

        for (int i = 0; i < arr.length; i++) {
            arr[i] *= factor;
        }
    }
}
