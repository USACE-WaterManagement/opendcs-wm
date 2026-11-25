/*
 *  As a work of the United States Government, this package
 *  is in the public domain within the United States.
 */
package spk.algo;

import decodes.sql.DbKey;
import decodes.tsdb.DataCollection;
import decodes.tsdb.DbCompAlgorithm;
import decodes.tsdb.DbCompParm;
import decodes.tsdb.DbComputation;
import java.util.Date;
import opendcs.dai.TimeSeriesDAI;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import opendcs.db.test.Fixtures;
import opendcs.db.test.TestDatabase;
import opendcs.db.test.UnitHelpers;

public class KalmanSmootherQRTest {

    KalmanSmootherQR instance = null;
    TestDatabase db = null;
    TimeSeriesDAI tsdai = null;
    Fixtures fixtures = null;
    Date start = null;
    Date end = null;

    public KalmanSmootherQRTest() throws Exception {
        db = new TestDatabase();
        tsdai = db.makeTimeSeriesDAO();
        fixtures = Fixtures.getFixtures(db);
        start = Fixtures.sdf.parse("2013-01-01T00:00:00+0000");
        end = Fixtures.sdf.parse("2019-10-01T00:00:00+0000");
    }

    @Before
    public void setUp() throws Exception {
        DbCompAlgorithm dbca = new DbCompAlgorithm("KalmanSmootherQR");
        dbca.setExecClass("spk.algo.KalmanSmootherQR");

        DbComputation comp = new DbComputation(DbKey.NullKey, "KalmanQRTest");
        comp.setAlgorithmName("KalmanSmootherQR");
        comp.setAlgorithm(dbca);

        DbCompParm in = new DbCompParm("rough",
                fixtures.getTimeSeriesKey("TEST.Flow.Inst.15Minutes.0.raw"),
                "15Minutes", "Flow.Inst.0.raw", 0);
        comp.addParm(in);

        DbCompParm out = new DbCompParm("smooth",
                fixtures.getTimeSeriesKey("TEST.Flow.Inst.15Minutes.0.calc"),
                "15Minutes", "Flow.Inst.0.calc", 0);
        comp.addParm(out);

        comp.prepareForExec(db);
        DataCollection dc = UnitHelpers.getCompData(comp, tsdai, start, end);
        instance = (KalmanSmootherQR) comp.getExecutive();
        UnitHelpers.prepForApply(instance, dc);
    }

    private void run(Date t0, double[] values) throws Exception {
        long stepMs = 15L * 60L * 1000L;
        Date last = new Date(t0.getTime() + (values.length - 1) * stepMs);
        UnitHelpers.setAggPeriodEnd(instance, last);

        instance.beforeTimeSlices();
        for (int i = 0; i < values.length; i++) {
            Date dt = new Date(t0.getTime() + i * stepMs);
            UnitHelpers.setBaseTime(instance, dt);
            instance.rough = values[i];
            instance.doAWTimeSlice();
        }
        instance.afterTimeSlices();
    }

    @Test
    public void testFlatInputConverges() throws Exception {
        double[] vals = new double[200];
        for (int i = 0; i < vals.length; i++) vals[i] = 10.0;

        run(Fixtures.sdf.parse("2018-07-10T00:00:00+0000"), vals);

        assertTrue("output should be produced", instance.smooth.isChanged());
        assertEquals("flat input should converge to the constant",
                10.0, instance.smooth.getDoubleValue(), 1e-6);
    }

    @Test
    public void testInsufficientSamplesNoOutput() throws Exception {
        instance.minSamplesNeeded = 10;
        double[] vals = { 1.0, 2.0, 3.0 };

        run(Fixtures.sdf.parse("2018-07-10T00:00:00+0000"), vals);

        assertFalse("output should not be produced when below minSamplesNeeded",
                instance.smooth.isChanged());
    }

    /**
     * Q/R ratio determines steady-state behavior — the same ratio with both
     * Q and R scaled together should give the same step response. This is
     * the key invariant that justifies the single-ratio formulation in
     * KalmanSmoother and confirms the explicit Q/R parameterization is
     * mathematically consistent with it.
     */
    @Test
    public void testRatioInvariance() throws Exception {
        double[] vals = new double[101];
        for (int i = 0; i < 100; i++) vals[i] = 0.0;
        vals[100] = 100.0;
        Date t0 = Fixtures.sdf.parse("2018-07-10T00:00:00+0000");

        instance.processNoise = 1e-6;
        instance.measurementNoise = 1.0;
        instance.initialVariance = 1.0;
        run(t0, vals);
        double a = instance.smooth.getDoubleValue();

        setUp();
        instance.processNoise = 1e-3;     // Q scaled by 1000
        instance.measurementNoise = 1e3;  // R scaled by 1000 — same ratio
        instance.initialVariance = 1e3;   // P0 must scale with R for the invariance to hold
        run(t0, vals);
        double b = instance.smooth.getDoubleValue();

        assertEquals("same Q/R ratio should give same response", a, b, 1e-6);
    }

    /**
     * Larger measurementNoise (with fixed Q) should produce smoother output —
     * each observation is trusted less, so the step response is smaller.
     */
    @Test
    public void testLargerRGivesLessResponse() throws Exception {
        double[] vals = new double[101];
        for (int i = 0; i < 100; i++) vals[i] = 0.0;
        vals[100] = 100.0;
        Date t0 = Fixtures.sdf.parse("2018-07-10T00:00:00+0000");

        instance.processNoise = 1e-6;
        instance.measurementNoise = 1.0;
        run(t0, vals);
        double trusting = instance.smooth.getDoubleValue();

        setUp();
        instance.processNoise = 1e-6;
        instance.measurementNoise = 1e6;  // distrust observations
        run(t0, vals);
        double skeptical = instance.smooth.getDoubleValue();

        assertTrue("both runs should respond upward", trusting > 1.0 && skeptical > 0.0);
        assertTrue("larger R should respond less than smaller R (skeptical="
                + skeptical + ", trusting=" + trusting + ")",
                skeptical < trusting);
    }
}
