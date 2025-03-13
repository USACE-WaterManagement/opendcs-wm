package mil.usace.army.swd.swt.algorithms;

import ilex.var.NamedVariableList;
import ilex.var.NamedVariable;
import decodes.tsdb.DbAlgorithmExecutive;
import decodes.tsdb.DbCompException;
import decodes.tsdb.DbIoException;
import decodes.tsdb.VarFlags;
import decodes.tsdb.algo.AWAlgoType;
import hec.data.cwmsRating.RatingSet;
import decodes.cwms.CwmsTimeSeriesDb;
import decodes.cwms.rating.CwmsRatingDao;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.ParmRef;
import ilex.var.TimedVariable;
import usace.cwms.db.dao.ifc.level.CwmsDbLevel;
import usace.cwms.db.dao.ifc.text.CwmsDbText;
import usace.cwms.db.dao.ifc.ts.CwmsDbTs;
import usace.cwms.db.dao.util.TimeValueQuality;
import usace.cwms.db.dao.util.services.CwmsDbServiceLookup;
import wcds.dbi.oracle.cwms.CwmsTextJdbcDao;
import decodes.tsdb.TimeSeriesIdentifier;

//AW:IMPORTS
// Place an import statements you need here.

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.CallableStatement;

import org.jdom.Element;
import org.jdom.JDOMException;
import org.opendcs.annotations.PropertySpec;
import org.opendcs.annotations.algorithm.Algorithm;
import org.opendcs.annotations.algorithm.Input;
import org.opendcs.annotations.algorithm.Output;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.util.*;
//AW:IMPORTS_END

@Algorithm(description = "This algorithm calculates pool storage and pool percent based on a storage value in a time series.")
public class PoolStorageAndPoolPercent extends decodes.tsdb.algo.AW_AlgorithmBase
{
    @Input
    public double storInst;

    @Output
    public NamedVariable poolStor = new NamedVariable("poolStor", 0);
    @Output
    public NamedVariable poolPercent = new NamedVariable("poolPercent", 0);
    

    @PropertySpec(value = "true", description = "Calculate if Over full")
    public boolean boolCalcOverFull = true;
    @PropertySpec(value = "true", description = "Calculate if Under full")
    public boolean boolCalcUnderFull = true;
    @PropertySpec()
    public String strPoolName = "";
    @PropertySpec(value="Elev;Stor.Linear")
    public String strElev2StorRatingTemplate = "Elev;Stor.Linear";
    @PropertySpec(value="Production")
    public String strElev2StorRatingSpecVersion = "Production";

    private Connection conn = null;
    private CwmsDbText cwmsText;
    private CwmsDbLevel cwmsLevel;
    private CwmsRatingDao cwmsRatingDao;

    // Allow javac to generate a no-args constructor.

    /**
     * Algorithm-specific initialization provided by the subclass.
     */
    @Override
    protected void initAWAlgorithm() throws DbCompException
    {
        _awAlgoType = AWAlgoType.TIME_SLICE;

    }

    /**
     * This method is called once before iterating all time slices.
     */
    @Override
    protected void beforeTimeSlices() throws DbCompException
    {
        conn = tsdb.getConnection();
        try
        {
            cwmsText = CwmsDbServiceLookup.buildCwmsDb(CwmsDbText.class, conn);
            cwmsLevel = CwmsDbServiceLookup.buildCwmsDb(CwmsDbLevel.class, conn);
            cwmsRatingDao = new CwmsRatingDao((CwmsTimeSeriesDb)tsdb);;
        }
        catch (SQLException ex)
        {
            tsdb.freeConnection(conn);
            throw new DbCompException("Unable to retrieve Cwms Text Utility.", ex);
        }
    }

    /**
     * Do the algorithm for a single time slice.
     * AW will fill in user-supplied code here.
     * Base class will set inputs prior to calling this method.
     * User code should call one of the setOutput methods for a time-slice
     * output variable.
     *
     * @throws DbCompException (or subclass thereof) if execution of this
     *        algorithm is to be aborted.
     */
    @Override
    protected void doAWTimeSlice() throws DbCompException
    {
        // Enter code to be executed at each time-slice.
        //get the connection object to the database from the protected member "TimeSeriesDb tsdb" of the base class of a CCP algorithm
        //call the base class method getParmTsId() with the role name.  That is, whatever your algorithm is calling the parameter (e.g. "inputParam", "indepThing") as it appears in the group box 'Input Time Series' above.
        TimeSeriesIdentifier tsid = getParmTsId("storInst");   //Interface TimeSeriesIdentifier is in package decodes.tsdb
        String strTsId = tsid.getUniqueString();
  
        String[] strArrResult = strTsId.split("\\.");  //this looks strange because the dot in regular expressions means "any character" so therefore you have to escape the dot
  
        //get and parse the XML CLOB that specifies the pool information
        
        
        try {
            //obtain the CLOB containing the XML that describes the min and max location levels for the pool
            
            String strLocation= strArrResult[0];
            String strTextKey = String.format("POOL.%s.%s", strLocation, strPoolName);
            String strCwmsText = cwmsText.retrieveText(conn, strTextKey);
            //now parse the XML into a jdom class object
            org.jdom.Document doc = null;
            Element root = null;
            org.jdom.input.SAXBuilder parser = new org.jdom.input.SAXBuilder(false);  //create a SAX parser with no validation           
            doc = parser.build(new ByteArrayInputStream(strCwmsText.getBytes("UTF-8")));            
            root = doc.getRootElement();
            //System.out.println("  rootname: "+ root.getName());
            //from the root document, navigate down to the min_loc_level_ref section and query for the string that contains it's id
            ArrayList arrlist1 = new ArrayList();
            arrlist1.addAll(root.getChildren("min_loc_level_ref"));
            Element elemSpecifiedLevelMin = (Element)arrlist1.get(0);
            //System.out.println("name is " + elemSpecifiedLevelMin.getName() );

            ArrayList arrlist2 = new ArrayList();
            arrlist2.addAll(elemSpecifiedLevelMin.getChildren("specified_level"));
            Element elemSpecifiedLevelMinId = (Element)arrlist2.get(0);
            //System.out.println("name is " + elemSpecifiedLevelMinId.getName() );
            String strPoolMinLevelId = elemSpecifiedLevelMinId.getAttributeValue("id");
            //System.out.println(" min_loc_level_ref id = " + strPoolMinLevelId);

            //from the root document, navigate down to the max_loc_level_ref section and query for the string that contains it's id
            ArrayList arrlist3 = new ArrayList();
            arrlist3.addAll(root.getChildren("max_loc_level_ref"));
            Element elemSpecifiedLevelMax = (Element)arrlist3.get(0);
            //System.out.println("name is " + elemSpecifiedLevelMax.getName() );

            ArrayList arrlist4 = new ArrayList();
            arrlist4.addAll(elemSpecifiedLevelMax.getChildren("specified_level"));
            Element elemSpecifiedLevelMaxId = (Element)arrlist4.get(0);
            //System.out.println("name is " + elemSpecifiedLevelMaxId.getName() );
            String strPoolMaxLevelId = elemSpecifiedLevelMaxId.getAttributeValue("id");
        
            String strLocLevelId = String.format("%s.Elev.Inst.0.%s", strLocation, strPoolMinLevelId);


            List<TimeValueQuality> result = cwmsLevel.retrieveLocationLevelValues(conn, strLocLevelId, "ft", _timeSliceBaseTime, _timeSliceBaseTime, null, null, null, aggTZ, null);

            double fElevAtMinLocLevel = result.get(0).getValue();

            strLocLevelId = String.format("%s.Elev.Inst.0.%s", strLocation, strPoolMaxLevelId);
            result = cwmsLevel.retrieveLocationLevelValues(conn, strLocLevelId, "ft", _timeSliceBaseTime, _timeSliceBaseTime, null, null, null, aggTZ, null);
            

            double fElevAtMaxLocLevel = result.get(0).getValue();
            String strRatingSpec = String.format("%s.%s.%s", strLocation, strElev2StorRatingTemplate, strElev2StorRatingSpecVersion); //of the form: "KEYS.Elev;Stor.Linear.Production"
            RatingSet ratingsSet = cwmsRatingDao.getRatingSet(strRatingSpec);
            ratingsSet.setDataUnits(conn, new String[]{getInputUnitsAbbr("storInst"),getParmUnitsAbbr(poolStor.getName())});
            double fStorAtMinLocLevel = ratingsSet.rate(conn, fElevAtMinLocLevel);
            double fStorAtMaxLocLevel = ratingsSet.rate(conn, fElevAtMaxLocLevel);
            double fStorAtTimeSlice = storInst;
            double fPoolStorTotal = fStorAtMaxLocLevel - fStorAtMinLocLevel;
            double fPoolStorFilled = fStorAtTimeSlice - fStorAtMinLocLevel;
            double fPoolFilledPercent = (fPoolStorFilled / fPoolStorTotal) * 100.0;
            if (fPoolFilledPercent > 100.0 && boolCalcOverFull)
            {
                    fPoolStorFilled = fPoolStorTotal;
                    fPoolFilledPercent = 100.0;
            } //end if (executes if the pool is filled to over 100% and the flag for whether to compute overages is false...in other words, cap the % full at 100%
            if (fPoolFilledPercent < 0.0 && boolCalcUnderFull)
            {
                    fPoolStorFilled = 0.0;
                    fPoolFilledPercent = 0.0;
            } //end if (executes if the pool is filled to over 100% and the flag for whether to compute overages is false...in other words, cap the % full at 100%
            setOutput(poolStor, fPoolStorFilled);
            setOutput(poolPercent, fPoolFilledPercent);

        } //end try block
        catch (Exception ex) 
        {
            throw new DbCompException("Unable to perform calculation.", ex);
        }
//AW:TIMESLICE_END
    }

    /**
     * This method is called once after iterating all time slices.
     */
    protected void afterTimeSlices()
            throws DbCompException
    {
        cwmsRatingDao.close();
        tsdb.freeConnection(conn);
    }
}
