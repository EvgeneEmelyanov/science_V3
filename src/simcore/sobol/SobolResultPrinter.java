package simcore.sobol;

import java.util.List;
import java.util.Locale;

public final class SobolResultPrinter {

    private static final Locale LOCALE_RU = Locale.forLanguageTag("ru-RU");

    private SobolResultPrinter() {}

    public static void printTable(
            List<SobolFactor> factors,
            SobolResult r
    ) {
        Locale.setDefault(LOCALE_RU);

        // Pooled statistics over Sobol sample means (A ∪ B)
        System.out.printf(
                "LCOE: var=%.6g std=%.6g range=[%.6g..%.6g]%n" +
                        "LOLE: var=%.6g std=%.6g range=[%.6g..%.6g]%n" +
                        "ENS: var=%.6g std=%.6g range=[%.6g..%.6g]%n" +
                        "Fuel: var=%.6g std=%.6g range=[%.6g..%.6g]%n" +
                        "Moto: var=%.6g std=%.6g range=[%.6g..%.6g]%n",
                r.getVar_lcoe(), Math.sqrt(r.getVar_lcoe()), r.getMin_lcoe(), r.getMax_lcoe(),
                r.getVar_lole(), Math.sqrt(r.getVar_lole()), r.getMin_lole(), r.getMax_lole(),
                r.getVar_ens(), Math.sqrt(r.getVar_ens()), r.getMin_ens(), r.getMax_ens(),
                r.getVar_fuel(), Math.sqrt(r.getVar_fuel()), r.getMin_fuel(), r.getMax_fuel(),
                r.getVar_moto(), Math.sqrt(r.getVar_moto()), r.getMin_moto(), r.getMax_moto()
        );

        System.out.println(
                "param\tS_LCOE\tST_LCOE\tS_LOLE\tST_LOLE\tS_ENS\tST_ENS\tS_Fuel\tST_Fuel\tS_Moto\tST_Moto"
        );

        for (int i = 0; i < factors.size(); i++) {
            System.out.printf(
                    "%s\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f\t%.2f%n",
                    factors.get(i).getName(),
                    r.getS_lcoe()[i],
                    r.getSt_lcoe()[i],
                    r.getS_lole()[i],
                    r.getSt_lole()[i],
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
