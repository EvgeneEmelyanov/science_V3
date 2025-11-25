package simcore.model;

import java.util.Random;

/**
 * Аккумуляторная батарея с:
 *  - деградацией максимальной ёмкости;
 *  - случайными отказами (как у другого оборудования);
 *  - ремонтом/заменой, после которого ёмкость восстанавливается.
 *
 * Важно:
 *  - maxCapacityKwh может уменьшаться извне (деградация), либо
 *    можно реализовать деградацию в местах использования АКБ;
 *  - здесь мы только проверяем порог деградации и случайный отказ.
 */
public class Battery extends Equipment {

    /** Порог деградации: 80% от номинальной ёмкости. */
    private static final double DEGRADATION_THRESHOLD = 0.8;

    /** Номинальная ёмкость АКБ, кВт·ч (начальное значение). */
    private final double nominalCapacityKwh;

    /** Текущая максимальная ёмкость АКБ с учётом деградации, кВт·ч. */
    private double maxCapacityKwh;

    /**
     * @param id                   id АКБ
     * @param capacityKwh          номинальная ёмкость, кВт·ч
     * @param failureRatePerYear   частота случайных отказов, 1/год
     * @param repairTimeHours      длительность ремонта/замены, ч
     */
    public Battery(int id,
                   double capacityKwh,
                   double failureRatePerYear,
                   int repairTimeHours) {
        super("BT", id, failureRatePerYear, repairTimeHours);
        this.nominalCapacityKwh = capacityKwh;
        this.maxCapacityKwh = capacityKwh;
    }

    public double getNominalCapacityKwh() {
        return nominalCapacityKwh;
    }

    public double getMaxCapacityKwh() {
        return maxCapacityKwh;
    }

    public void setMaxCapacityKwh(double maxCapacityKwh) {
        this.maxCapacityKwh = maxCapacityKwh;
    }

    @Override
    public void initFailureModel(Random rnd, boolean considerFailures) {
        super.initFailureModel(rnd, considerFailures);
        // При старте прогона считаем, что ёмкость = текущая (уже деградированная),
        // а восстановление до номинала произойдёт только после ремонта.
    }

    /**
     * Обновление состояния отказа/ремонта/деградации на один час.
     *
     * Логика:
     *  1) сначала базовая логика ремонтов/случайных отказов (super.updateFailureOneHour);
     *  2) если АКБ в ремонте или уже в отказе — выходим;
     *  3) если maxCapacityKwh <= порога от номинала — считаем деградационный отказ,
     *     отправляем АКБ в ремонт с временем getRepairTimeHours();
     *  4) по завершении ремонта (в базовом коде) ёмкость восстанавливается до номинала.
     */
    @Override
    public void updateFailureOneHour(boolean considerFailures) {

        // Сначала обычные случайные отказы + ремонт из базового Equipment
        super.updateFailureOneHour(considerFailures);

        // Если идёт ремонт или мы уже в отказе — ничего не делаем
        if (repairDurationHours > 0 || !status) {
            return;
        }

        // timeWorkedHours увеличивается снаружи, если аккумулятор реально использовался.
        // Здесь только проверяем порог деградации.
        if (maxCapacityKwh <= DEGRADATION_THRESHOLD * nominalCapacityKwh) {
            status = false;
            failureCount++;
            repairDurationHours = getRepairTimeHours();
        }
    }

    /**
     * После ремонта считаем, что установлена новая батарея с номинальной ёмкостью.
     */
    @Override
    protected void onRepairFinished() {
        this.maxCapacityKwh = this.nominalCapacityKwh;
    }
}
