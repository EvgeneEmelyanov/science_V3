package simcore.sobol;

import java.util.List;
import java.util.Locale;

public final class SobolResultPrinter {

    private SobolResultPrinter() {}

    public static void printTable(
            List<SobolFactor> factors,
            SobolResult r
    ) {
        Locale.setDefault(Locale.US);

        System.out.println(
                "param\tS_ENS\tST_ENS\tS_Fuel\tST_Fuel\tS_Moto\tST_Moto"
        );

        for (int i = 0; i < factors.size(); i++) {
            System.out.printf(
                    "%s\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f%n",
                    factors.get(i).getName(),
                    r.getS_ens()[i],
                    r.getSt_ens()[i],
                    r.getS_fuel()[i],
                    r.getSt_fuel()[i],
                    r.getS_moto()[i],
                    r.getSt_moto()[i]
            );
        }
    }
}
