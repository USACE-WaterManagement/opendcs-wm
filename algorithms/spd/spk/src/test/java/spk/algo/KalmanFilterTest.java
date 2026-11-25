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

public class KalmanFilterTest {

    KalmanFilter instance = null;
    TestDatabase db = null;
    TimeSeriesDAI tsdai = null;
    Fixtures fixtures = null;
    Date start = null;
    Date end = null;

    public KalmanFilterTest() throws Exception {
        db = new TestDatabase();
        tsdai = db.makeTimeSeriesDAO();
        fixtures = Fixtures.getFixtures(db);
        start = Fixtures.sdf.parse("2013-01-01T00:00:00+0000");
        end = Fixtures.sdf.parse("2019-10-01T00:00:00+0000");
    }

    @Before
    public void setUp() throws Exception {
        DbCompAlgorithm dbca = new DbCompAlgorithm("KalmanFilter");
        dbca.setExecClass("spk.algo.KalmanFilter");

        DbComputation comp = new DbComputation(DbKey.NullKey, "KalmanFilterTest");
        comp.setAlgorithmName("KalmanFilter");
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
        instance = (KalmanFilter) comp.getExecutive();
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
                10.0, instance.smooth.getDoubleValue(), 1e-3);
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

    /** Missing inputs are not counted toward minSamplesNeeded. */
    @Test
    public void testMissingInputsExcluded() throws Exception {
        instance.minSamplesNeeded = 5;
        double[] vals = new double[10];
        for (int i = 0; i < vals.length; i++) vals[i] = Double.NEGATIVE_INFINITY; // isMissing

        run(Fixtures.sdf.parse("2018-07-10T00:00:00+0000"), vals);

        assertFalse("output should not be produced when all samples are missing",
                instance.smooth.isChanged());
    }

    /**
     * Local-level mode (useSlope=false) should converge to a constant input
     * just like slope mode. Sanity check that the alternate code path runs
     * and produces the right answer on flat data.
     */
    @Test
    public void testLocalLevelFlatInputConverges() throws Exception {
        double[] vals = new double[200];
        for (int i = 0; i < vals.length; i++) vals[i] = 10.0;

        instance.useSlope = false;
        instance.processNoiseCovariance = 5e-4; // local-level units (qa)
        instance.observationNoiseCovariance = 0.5;
        instance.initAWAlgorithm();
        run(Fixtures.sdf.parse("2018-07-10T00:00:00+0000"), vals);

        assertTrue("output should be produced", instance.smooth.isChanged());
        assertEquals("flat input in local-level mode should converge to the constant",
                10.0, instance.smooth.getDoubleValue(), 1e-3);
    }

    /**
     * The defining property of local-level mode: monotone convergence to a
     * step input, no slope-momentum overshoot. With useSlope=true the same
     * input can produce an output above the step value (slope state carries
     * level past it); with useSlope=false the output must stay at or below.
     */
    @Test
    public void testLocalLevelDoesNotOvershootStep() throws Exception {
        double[] vals = new double[100];
        for (int i = 0; i < 50; i++) vals[i] = 0.0;
        for (int i = 50; i < 100; i++) vals[i] = 100.0;
        Date t0 = Fixtures.sdf.parse("2018-07-10T00:00:00+0000");

        instance.minSamplesNeeded = 10;
        instance.useSlope = false;
        instance.processNoiseCovariance = 5e-4; // local-level units
        instance.observationNoiseCovariance = 0.5;
        instance.initAWAlgorithm();
        run(t0, vals);

        assertTrue("output should be produced", instance.smooth.isChanged());
        double out = instance.smooth.getDoubleValue();
        assertTrue("local-level output (" + out + ") must not exceed step value 100",
                out <= 100.0 + 1e-9);
        assertTrue("local-level output (" + out + ") should respond toward 100, not stay near 0",
                out > 50.0);
    }

    /**
     * Damped trend (useSlope=true with slopeDamping<1) should reduce
     * overshoot at step transitions compared to pure local-linear-trend
     * (slopeDamping=1). The slope state still tracks ramps but decays
     * toward zero each step, so a step transient can't accumulate
     * unbounded slope momentum.
     */
    @Test
    public void testSlopeDampingReducesOvershoot() throws Exception {
        double[] vals = new double[100];
        for (int i = 0; i < 50; i++) vals[i] = 0.0;
        for (int i = 50; i < 100; i++) vals[i] = 100.0;
        Date t0 = Fixtures.sdf.parse("2018-07-10T00:00:00+0000");

        instance.minSamplesNeeded = 10;
        instance.useSlope = true;
        instance.processNoiseCovariance = 1e-12;
        instance.observationNoiseCovariance = 0.5;
        instance.slopeDamping = 1.0; // pure local-linear-trend
        instance.initAWAlgorithm();
        run(t0, vals);
        double undamped = instance.smooth.getDoubleValue();

        setUp();
        instance.minSamplesNeeded = 10;
        instance.useSlope = true;
        instance.processNoiseCovariance = 1e-12;
        instance.observationNoiseCovariance = 0.5;
        instance.slopeDamping = 0.85;
        instance.initAWAlgorithm();
        run(t0, vals);
        double damped = instance.smooth.getDoubleValue();

        // Both should respond toward 100. Damped should be closer to (or
        // below) the step value than undamped, since damping cuts the
        // slope-driven overshoot.
        assertTrue("undamped should respond (got " + undamped + ")", undamped > 50.0);
        assertTrue("damped should respond (got " + damped + ")", damped > 50.0);
        double undampedOvershoot = Math.max(0.0, undamped - 100.0);
        double dampedOvershoot = Math.max(0.0, damped - 100.0);
        assertTrue("damped trend should overshoot less than undamped"
                + " (damped overshoot=" + dampedOvershoot
                + ", undamped overshoot=" + undampedOvershoot + ")",
                dampedOvershoot <= undampedOvershoot);
    }

    /**
     * Larger observationNoiseCovariance (with q fixed) should produce a less
     * faithful estimate of the post-step level — the trusting filter settles
     * on the new value, while the skeptical filter either lags well below or
     * overshoots well above it (the local-linear-trend model lets the slope
     * state pick up momentum during the step and carry the level past it).
     * Either way, distance-from-truth is larger for the skeptical run.
     */
    @Test
    public void testLargerRGivesLessResponse() throws Exception {
        double[] vals = new double[100];
        for (int i = 0; i < 50; i++) vals[i] = 0.0;
        for (int i = 50; i < 100; i++) vals[i] = 100.0;
        Date t0 = Fixtures.sdf.parse("2018-07-10T00:00:00+0000");

        instance.minSamplesNeeded = 10;
        instance.processNoiseCovariance = 1e-12;
        instance.observationNoiseCovariance = 0.5;
        instance.initAWAlgorithm(); // rebuild R from the updated properties
        run(t0, vals);
        double trusting = instance.smooth.getDoubleValue();

        setUp();
        instance.minSamplesNeeded = 10;
        instance.processNoiseCovariance = 1e-12;
        instance.observationNoiseCovariance = 1e6; // distrust observations
        instance.initAWAlgorithm();
        run(t0, vals);
        double skeptical = instance.smooth.getDoubleValue();

        double trustingDist = Math.abs(trusting - 100.0);
        double skepticalDist = Math.abs(skeptical - 100.0);
        assertTrue("trusting filter should track the step closely (got " + trusting + ")",
                trustingDist < 5.0);
        assertTrue("skeptical filter should deviate more from the step value than trusting"
                + " (skeptical=" + skeptical + ", trusting=" + trusting + ")",
                skepticalDist > trustingDist);
    }
}
