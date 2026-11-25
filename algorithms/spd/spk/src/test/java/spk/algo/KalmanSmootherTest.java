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

public class KalmanSmootherTest {

    KalmanSmoother instance = null;
    TestDatabase db = null;
    TimeSeriesDAI tsdai = null;
    Fixtures fixtures = null;
    Date start = null;
    Date end = null;

    public KalmanSmootherTest() throws Exception {
        db = new TestDatabase();
        tsdai = db.makeTimeSeriesDAO();
        fixtures = Fixtures.getFixtures(db);
        start = Fixtures.sdf.parse("2013-01-01T00:00:00+0000");
        end = Fixtures.sdf.parse("2019-10-01T00:00:00+0000");
    }

    @Before
    public void setUp() throws Exception {
        DbCompAlgorithm dbca = new DbCompAlgorithm("KalmanSmoother");
        dbca.setExecClass("spk.algo.KalmanSmoother");

        DbComputation comp = new DbComputation(DbKey.NullKey, "KalmanTest");
        comp.setAlgorithmName("KalmanSmoother");
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
        instance = (KalmanSmoother) comp.getExecutive();
        UnitHelpers.prepForApply(instance, dc);
    }

    /** Run the filter over n synthetic samples spaced 15 min apart starting at t0. */
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

    /** Constant input: filter should converge to that constant. */
    @Test
    public void testFlatInputConverges() throws Exception {
        double[] vals = new double[200];
        for (int i = 0; i < vals.length; i++) vals[i] = 10.0;

        run(Fixtures.sdf.parse("2018-07-10T00:00:00+0000"), vals);

        assertTrue("output should be produced", instance.smooth.isChanged());
        assertEquals("flat input should converge to the constant",
                10.0, instance.smooth.getDoubleValue(), 1e-6);
    }

    /** Below minSamplesNeeded: no output. */
    @Test
    public void testInsufficientSamplesNoOutput() throws Exception {
        instance.minSamplesNeeded = 10;
        double[] vals = { 1.0, 2.0, 3.0 };

        run(Fixtures.sdf.parse("2018-07-10T00:00:00+0000"), vals);

        assertFalse("output should not be produced when below minSamplesNeeded",
                instance.smooth.isChanged());
    }

    /**
     * Perfect linear ramp with an aggressive q: the local-linear-trend filter
     * should latch the slope and track the ramp closely by the end.
     */
    @Test
    public void testLinearRampTracked() throws Exception {
        instance.q = 1.0;       // very responsive — no reason to lag a noiseless ramp
        double slopePerStep = 0.5;
        double[] vals = new double[200];
        for (int i = 0; i < vals.length; i++) vals[i] = i * slopePerStep;
        double truth = vals[vals.length - 1];

        run(Fixtures.sdf.parse("2018-07-10T00:00:00+0000"), vals);

        assertTrue("output should be produced", instance.smooth.isChanged());
        assertEquals("filter should track a noiseless ramp",
                truth, instance.smooth.getDoubleValue(), 0.5);
    }

    /**
     * maxSamples cap: with the cap set to N, the result must equal what we'd
     * get from running on only the most recent N inputs. Verifies the cap is
     * a true tail-window restriction, not just an array-size guard.
     */
    @Test
    public void testMaxSamplesCapMatchesShortRun() throws Exception {
        double[] full = new double[200];
        for (int i = 0; i < full.length; i++) full[i] = Math.sin(i * 0.1);
        double[] tail = new double[50];
        System.arraycopy(full, 150, tail, 0, 50);
        Date t0 = Fixtures.sdf.parse("2018-07-10T00:00:00+0000");
        Date t0Tail = new Date(t0.getTime() + 150L * 15L * 60L * 1000L);

        instance.maxSamples = 50;
        run(t0, full);
        double capped = instance.smooth.getDoubleValue();

        setUp();
        run(t0Tail, tail);
        double tailOnly = instance.smooth.getDoubleValue();

        assertEquals("capped run should equal short tail run",
                tailOnly, capped, 1e-9);
    }

    /**
     * Comparative smoothness check: given the same step input, a smaller q
     * (more smoothing) must produce a response no larger than a larger q
     * (more responsive). This is the directional invariant of the q knob.
     */
    @Test
    public void testSmallerQGivesLessResponse() throws Exception {
        double[] vals = new double[101];
        for (int i = 0; i < 100; i++) vals[i] = 0.0;
        vals[100] = 100.0;
        Date t0 = Fixtures.sdf.parse("2018-07-10T00:00:00+0000");

        instance.q = 1.0;
        run(t0, vals);
        double responsive = instance.smooth.getDoubleValue();

        setUp(); // fresh instance
        instance.q = 1e-12;
        run(t0, vals);
        double smooth = instance.smooth.getDoubleValue();

        assertTrue("both runs should respond upward", responsive > 1.0 && smooth > 1.0);
        assertTrue("smaller q should respond less than larger q (smooth=" + smooth
                + ", responsive=" + responsive + ")",
                smooth < responsive);
    }
}
