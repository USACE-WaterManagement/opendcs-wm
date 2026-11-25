/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package spk.apps;
import decodes.consumer.DataConsumerException;
import decodes.consumer.OutputFormatter;
import decodes.consumer.OutputFormatterException;
import decodes.consumer.PipeConsumer;
import decodes.datasource.GoesPMParser;
import decodes.datasource.RawMessage;
import decodes.datasource.UnknownPlatformException;
import decodes.db.Constants;
import decodes.db.DataPresentation;
import decodes.db.DataType;
import decodes.db.Database;
import decodes.db.InvalidDatabaseException;
import decodes.db.PresentationGroup;
import decodes.db.TransportMedium;
import decodes.decoder.DecodedMessage;
import decodes.decoder.Sensor;
import decodes.decoder.TimeSeries;
import decodes.hdb.HdbTsId;
import decodes.sql.DbKey;
import decodes.tsdb.BadTimeSeriesException;
import decodes.tsdb.CTimeSeries;
import decodes.tsdb.CpCompDependsRecord;
import decodes.tsdb.DataCollection;
import decodes.tsdb.TsdbAppTemplate;
import decodes.tsdb.DbComputation;
import decodes.tsdb.DbCompParm;
import decodes.tsdb.DbCompResolver;
import decodes.tsdb.DbIoException;
import decodes.tsdb.GroupHelper;
import decodes.tsdb.NoSuchObjectException;
import decodes.tsdb.TimeSeriesIdentifier;
import decodes.tsdb.TsGroup;
import decodes.tsdb.VarFlags;
import decodes.util.CmdLineArgs;
import decodes.util.TSUtil;
import ilex.cmdline.BooleanToken;
import ilex.cmdline.StringToken;
import ilex.cmdline.TokenOptions;
import ilex.util.EnvExpander;
import ilex.util.TextUtil;
import ilex.var.TimedVariable;
import ilex.var.Variable;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Properties;
import java.util.TimeZone;
import lrgs.gui.DecodesInterface;
import opendcs.dai.CompDependsDAI;
import opendcs.dai.ComputationDAI;
import opendcs.dai.TimeSeriesDAI;
import opendcs.dai.TsGroupDAI;
import org.opendcs.utils.logging.OpenDcsLoggerFactory;

/**
 *
 * @author L2EDDMAN
 */
public class BatchComp extends TsdbAppTemplate {

    private String dateSpec = "yyyy/MM/dd-HH:mm:ss";
    private SimpleDateFormat sdf = new SimpleDateFormat(dateSpec);
    
    private StringToken tsidToken = new StringToken("T", "TSID(s)", "", 
            TokenOptions.optSwitch|TokenOptions.optMultiple, null);
    private StringToken groupIdToken = new StringToken("G", "Group ID", "", 
            TokenOptions.optSwitch|TokenOptions.optMultiple, null);
    private StringToken compIdToken = new StringToken("C", "Computation ID(s)", "",
            TokenOptions.optSwitch|TokenOptions.optMultiple, null);
    private StringToken ctrlFileToken = new StringToken("f", "Control File", "", 
            TokenOptions.optSwitch, null);
    private StringToken sinceToken = new StringToken("S", "Since Time in " + dateSpec, "", 
            TokenOptions.optSwitch, null);
    private StringToken untilToken = new StringToken("U", "Until Time in " + dateSpec, "", 
            TokenOptions.optSwitch, sdf.format(Date.from(Instant.now())));
    private StringToken outputFmtToken = new StringToken("o", "Output Format", "",
            TokenOptions.optSwitch, null);
    private StringToken presGrpToken = new StringToken("R", "Presentation Group", "",
            TokenOptions.optSwitch, null);
    private BooleanToken quietToken = new BooleanToken("q", "Quiet - no prompts or stats.", "",
            TokenOptions.optSwitch, false);
    private BooleanToken recurseToken = new BooleanToken("r", "Recursive - process comp outputs too.", "",
            TokenOptions.optSwitch, false);
    private BooleanToken recurseAlwaysToken = new BooleanToken("e", "Always recurse, even if no outputs changed.", "",
            TokenOptions.optSwitch, false);
    private StringToken tzArg = new StringToken("Z", "Time Zone", "", 
            TokenOptions.optSwitch, "UTC");
    private BooleanToken yesToken = new BooleanToken("y", "Yes - supply yes to all prompts.", "",
            TokenOptions.optSwitch, false);
    private BooleanToken andToken = new BooleanToken("A", "AND - restrict group comps to user-specified TSIDs.", "",
            TokenOptions.optSwitch, false);
    
    private HashSet<TimeSeriesIdentifier> tsids = new HashSet<TimeSeriesIdentifier>();
    private ArrayList<DbComputation> specifiedComps = new ArrayList<DbComputation>();
    private Date since = null, until = null;

    private TimeSeriesDAI timeSeriesDAO = null;
    private ComputationDAI computationDAO = null;
    private PresentationGroup presGrp = null;
    private OutputFormatter outputFormatter = null;
    private TsGroupDAI tsGroupDAO = null;
    private CompDependsDAI compDependsDAO = null;
    private GroupHelper groupHelper = null;
    private boolean doingFullEval = false;
    private HashSet<CpCompDependsRecord> toAdd = new HashSet<CpCompDependsRecord>();
    
    public BatchComp(){
        super(BatchComp.class.getSimpleName() + ".log");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    }
    
    @Override
    protected void runApp() throws Exception {
        //System.out.println( "Comp name: " + this.compname.getValue() );
        logAndWrite( "Loading...");
        DecodesInterface.initDecodesMinimal(cmdLineArgs.getPropertiesFile());
        
        timeSeriesDAO = theDb.makeTimeSeriesDAO();
        groupHelper = theDb.makeGroupHelper();
        computationDAO = theDb.makeComputationDAO();
        compDependsDAO = theDb.makeCompDependsDAO();
        
        try {
            tsGroupDAO = theDb.makeTsGroupDAO();
            initFromArguments();
            tsGroupDAO.close();
            
            ArrayList<TimeSeriesIdentifier> outputTs = new ArrayList<>();
            do {
                outputTs.clear();
                runComps(outputTs);
                
                // If there's no comps, nothing ran, we're at the end
                if(specifiedComps.size() == 0)
                    break;
                
                tsids.clear();
                specifiedComps.clear();
                
                tsids.addAll(outputTs);
            } while(recurseToken.getValue() && (recurseAlwaysToken.getValue() || outputTs.size() > 0));
        }
        finally {
            compDependsDAO.close();
            computationDAO.close();
            timeSeriesDAO.close();
        }
    }
    
    private void runComps(ArrayList<TimeSeriesIdentifier> outputTs) throws DbIoException, NoSuchObjectException
    {
        buildCompList();

        if(specifiedComps.size() == 0)
        {
            logAndWrite("No comps to run.");
            return;
        }
        
        // Fetch the data for the specified time range for the specified TSIDs.
        DataCollection theData = new DataCollection();
        getInputTs(theData);
        
        ArrayList<DbComputation> toRun = new ArrayList<>();
        prepareComps(toRun);

        runComps(toRun, theData);
        
        outputOrSaveData(theData, outputTs);
    }

    private void initFromArguments() throws DbIoException, NoSuchObjectException
    {
        logAndWrite( "Processing arguments...");
        // If ctrl file provided it has everything.
        if (ctrlFileToken.getValue() != null)
            readControlFile(ctrlFileToken.getValue());
        
        if(recurseToken.getValue() && outputFmtToken.getValue() != null)
        {
            System.err.println("Recurse isn't compatible with formatted output.");
            System.exit(1);
        }

        if(recurseAlwaysToken.getValue() && !recurseToken.getValue())
        {
            System.err.println("Recurse always (-a) must be specified with recurse (-r).");
            System.exit(1);
        }
        
        for(int idx = 0; idx < tsidToken.NumberOfValues(); idx++)
            if (tsidToken.getValue(idx) != null)
                loadTsid(tsidToken.getValue(idx));

        for(int idx = 0; idx < groupIdToken.NumberOfValues(); idx++)
            if (groupIdToken.getValue(idx) != null)
                loadGroup(groupIdToken.getValue(idx));

        for(int idx = 0; idx < compIdToken.NumberOfValues(); idx++)
            if (compIdToken.getValue(idx) != null)
                loadComp(compIdToken.getValue(idx));

        if (sinceToken.getValue() != null)
        {
            try { since = sdf.parse(sinceToken.getValue()); }
            catch(ParseException ex)
            {
                System.err.println("Invalid Since Argument '" + sinceToken.getValue()
                    + "' -- format must be '" + dateSpec + " UTC");
                System.exit(1);
            }
        }
        if (untilToken.getValue() != null)
        {
            try { until = sdf.parse(untilToken.getValue()); }
            catch(ParseException ex)
            {
                System.err.println("Invalid Since Argument '" + untilToken.getValue()
                    + "' -- format must be '" + dateSpec + " UTC");
                System.exit(1);
            }
        }

        // if outputFmt and presGrp are provided, initialize them.
        String pgName = presGrpToken.getValue();
        if (pgName != null && pgName.length() > 0)
        {
            presGrp = Database.getDb().presentationGroupList.find(pgName);
            try
            {
                if (presGrp != null)
                    presGrp.prepareForExec();
            }
            catch (InvalidDatabaseException ex)
            {
                System.err.println("Cannot initialize presentation group '" 
                    + pgName + ": " + ex + " -- will not use.");
                presGrp = null;
            }
        }

        if (outputFmtToken.getValue() != null)
        {
            try
            {
                outputFormatter = OutputFormatter.makeOutputFormatter(
                    outputFmtToken.getValue(), TimeZone.getTimeZone(tzArg.getValue()),
                    presGrp, cmdLineArgs.getCmdLineProps(), null);
            }
            catch (OutputFormatterException ex)
            {
                System.err.println("Cannot make output formatter: " + ex);
                System.exit(1);
            }

        }

        info("" + specifiedComps.size() + " computations and "+ tsids.size()
            + " time series IDs supplied.");
    }

    private void buildCompList() throws DbIoException, NoSuchObjectException
    {
        logAndWrite("Building computation list...");
        // Now all arguments are read either from cmd line or control file.
        if (tsids.size() == 0 && specifiedComps.size() == 0)
        {
            System.out.println("Nothing to do -- No time series or computations specified.");
            System.exit(1);
        }
        else if (specifiedComps.size() > 0 && tsids.size() == 0)
        {
            // Comps but no TSIDs are specified. Find all possible inputs for the named comps.
            info("Comps but no TSIDs supplied, will use all possible triggers.");
            for(DbComputation comp : specifiedComps)
            {
                comp.setEnabled(true);
                tsids.addAll(computeTriggersFor(comp));
            }
            info("After evaluating triggers, we have " + tsids.size() + " TSIDs.");
        }
        else if (specifiedComps.size() == 0 && tsids.size() > 0)
        {
            System.out.println("Getting computations from TSIDs.");
            // Determine the possible enabled computations for the specified TSIDs
            // use CP_COMP_DEPENDS.
            for(DbKey compId : compDependsDAO.getCompIdsFor(tsids, DbKey.NullKey))
                specifiedComps.add(computationDAO.getComputationById(compId));
        }
        else
        {
            // both comps and TSIDs are specified. Nothing to do.
            System.out.println("No building necessary.");
        }       
    }
    
    private ArrayList<TimeSeriesIdentifier> computeTriggersFor(DbComputation comp) throws DbIoException, NoSuchObjectException
    {
        evalComp(comp);
        ArrayList<TimeSeriesIdentifier> ret = new ArrayList<>();
        for(CpCompDependsRecord ccdr : toAdd)
            ret.add(timeSeriesDAO.getTimeSeriesIdentifier(ccdr.getTsKey()));

        return ret;
    }

    private void getInputTs(DataCollection theData)
    {
        logAndWrite("Reading input time series data...");
        // Sort so that, as we go through the TSIDs, it's easy to detect duplicates.
        TimeSeriesIdentifier lastTsid = null;
        for(TimeSeriesIdentifier tsid : tsids)
        {
            if (lastTsid != null && lastTsid.equals(tsid))
                continue;
            CTimeSeries cts;
            try
            {
                cts = timeSeriesDAO.makeTimeSeries(tsid);
                int n = timeSeriesDAO.fillTimeSeries(cts, since, until);
                info("Read tsid '" + tsid.getUniqueString() + "' since="
                    + (since==null ? "null" : sdf.format(since)) + ", until="
                    + (until==null ? "null" : sdf.format(until))
                    + ", result=" + n + " values.");
                // Set the flag so that every value read is treated as a trigger.
                for(int idx = 0; idx < n; idx++)
                    VarFlags.setWasAdded(cts.sampleAt(idx));
                theData.addTimeSeries(cts);
            }
            catch (Exception ex)
            {
                String msg = "Error fetching input data: " + ex;
                warning(msg);
                System.err.print(msg);
                ex.printStackTrace(System.err);
            }
            lastTsid = tsid;
            logAndWrite("  " + tsid.getDisplayName());
        }
    }

    private void prepareComps(ArrayList<DbComputation> toRun) throws DbIoException, NoSuchObjectException
    {
        logAndWrite("Preparing computations...");
        // The resolver will make concrete clones and provide a complete list of all computations
        // that are possible with the given input data. It will also remove dups.
        DbCompResolver resolver = new DbCompResolver(theDb);
//		DbComputation[] resolved = resolver.resolve(theData);

        // If comps were specified on cmd line or ctrl file, filter the list by the specified ones.
        for(DbComputation comp : specifiedComps)
        {
            if (comp.hasGroupInput())
            {
                ArrayList<TimeSeriesIdentifier> triggers = computeTriggersFor(comp);
                if (andToken.getValue())
                    triggers.retainAll(tsids);
                for (TimeSeriesIdentifier tsid : triggers)
                {
                    try
                    {
                        DbComputation concrete = DbCompResolver.makeConcrete(theDb, timeSeriesDAO, tsid, comp, false);
                    
                        if (concrete != null)
                            // The resolver's addToResults method gets rid of duplicates.
                            resolver.addToResults(toRun, concrete, null);
                    }
                    catch (NoSuchObjectException ex)
                    {
                        logAndWrite("Error with computation: " + comp.getUniqueName() + "; TSID: " + tsid.getUniqueString(), true);
                        logAndWrite(ex.getLocalizedMessage(), true);
                    }
                }
            }
            else // non-group comp, no need to make concrete.
                toRun.add(comp);

            if (specifiedComps.size() > 0)
            {
                boolean found = false;
                for(DbComputation specifiedComp : specifiedComps)
                    if (specifiedComp.getKey().equals(comp))
                    {
                        found = true;
                        break;
                    }
                if (!found)
                    continue;
            }
            toRun.add(comp);
            logAndWrite("  " + comp.getName());
        }

        if (!quietToken.getValue())
        {
            System.out.println("since="
                + (since==null ? "anytime" : sdf.format(since))
                + ", until="
                + (until==null ? "anytime" : sdf.format(until)) + " UTC");
            System.out.println("# comps=" + specifiedComps.size() + ", # TSIDs=" + tsids.size());
            
            logAndWrite("Computations:");
            for(DbComputation comp: specifiedComps)
                logAndWrite(comp.getName());

            if(!yesToken.getValue())
            {
                System.out.println("Okay to proceed (y/n)? ");
                String x = System.console().readLine();
                if (x == null || !(x.toLowerCase().startsWith("y")))
                    System.exit(0);
            }
        }
        else System.err.println("quietmode=true");
    }
    
    private void runComps(ArrayList<DbComputation> toRun, DataCollection theData) throws DbIoException, NoSuchObjectException
    {
        logAndWrite("Executing computations...");
        // Execute the computations
        for(DbComputation comp2run : toRun)
        {
            try
            {
                info("Executing computation " + comp2run.getName());
                comp2run.prepareForExec(theDb);
                comp2run.apply(theData, theDb);
            }
            catch (Exception ex)
            {
                String msg = "Error executing comp '" + comp2run.getName() + "': " + ex;
                warning(msg);
            }
        }
    }
    
    private void outputOrSaveData(DataCollection theData, ArrayList<TimeSeriesIdentifier> outputTs) throws DbIoException
    {
        logAndWrite("Saving output...");
        // if an output format is specified, format the data and send to stdout
        if (outputFormatter != null)
        {
            byte[] dummyData = new byte[0];
            RawMessage rawMsg = new RawMessage(dummyData);
            rawMsg.setPlatform(null);
            rawMsg.setTransportMedium(null);
            String ttype="site";
            String tidArg="unknown";
            rawMsg.setTransportMedium(new TransportMedium(null, ttype, tidArg));
            rawMsg.setTimeStamp(new Date());
            rawMsg.setHeaderLength(0);
            rawMsg.setPM(GoesPMParser.MESSAGE_TIME, new Variable(new Date()));
            rawMsg.setPM(GoesPMParser.MESSAGE_LENGTH, new Variable(0L));

            try
            {
                outputTimeSeries(rawMsg, theData.getAllTimeSeries());
            }
            catch (Exception ex)
            {
                warning("Cannot output results: " + ex);
                System.err.println("Cannot output results: " + ex);
                ex.printStackTrace(System.err);
            }
        }
        else // Else write directly to database.
        {
            for(CTimeSeries cts : theData.getAllTimeSeries())
            {
                int numChanges = 0;
                Date earliest=null, latest=null;
                for(int idx = 0; idx < cts.size(); idx++)
                {
                    TimedVariable tv = cts.sampleAt(idx);
                    if (VarFlags.mustWrite(tv) || VarFlags.mustDelete(tv))
                    {
                        numChanges++;
                        if (earliest == null)
                            earliest = tv.getTime();
                        latest = tv.getTime();
                    }
                }

                if (numChanges > 0 || recurseAlwaysToken.getValue())
                {
                    // Make note of the changed output, so we can run the comps dependent on this later
                    outputTs.add(cts.getTimeSeriesIdentifier());
                }
                
                if (numChanges > 0)
                {
                    TimeSeriesIdentifier tsid = cts.getTimeSeriesIdentifier();
                    if(tsid == null)
                    {
                        info("Invalid time series name for " + cts.getDisplayName());
                        continue;
                    }
                    String s = "Writing " + numChanges + " values for time series " 
                        + tsid.getUniqueString()
                        + ", earliest=" + sdf.format(earliest) + ", latest=" + sdf.format(latest);
                    info(s);
                    if (!quietToken.getValue())
                        System.out.println(s);
                    try { timeSeriesDAO.saveTimeSeries(cts); }
                    catch(BadTimeSeriesException ex)
                    {
                        warning("Cannot save time series '" + cts.getTimeSeriesIdentifier() + "': " + ex);
                    }
                }
            }
        }
    }
    
    /**
     * This method adds a command line argument to allow
     * the user to turn off the Db Computations list filter.
     * By default is on.
     */
    @Override
    protected void addCustomArgs(CmdLineArgs cmdLineArgs)
    {
        cmdLineArgs.addToken(ctrlFileToken);
        cmdLineArgs.addToken(compIdToken);
        cmdLineArgs.addToken(tsidToken);
        cmdLineArgs.addToken(groupIdToken);
        cmdLineArgs.addToken(sinceToken);
        cmdLineArgs.addToken(untilToken);
        cmdLineArgs.addToken(outputFmtToken);
        cmdLineArgs.addToken(presGrpToken);
        cmdLineArgs.addToken(tzArg);
        cmdLineArgs.addToken(quietToken);
        cmdLineArgs.addToken(recurseToken);
        cmdLineArgs.addToken(recurseAlwaysToken);
        cmdLineArgs.addToken(yesToken);
        cmdLineArgs.addToken(andToken);
        
        appNameArg.setDefaultValue("utility");
    }

    /** Main method. Used when running from the runcomp script */
    public static void main(String[] args)
    {
        DecodesInterface.setGUI(false);
        DecodesInterface.silent = true;
        TsdbAppTemplate app = new BatchComp();
        try{ app.execute(args); }
        catch(Exception ex)
        {
            System.err.println(ex.getMessage());
            ex.printStackTrace(System.err);
        }
    }

    private void logAndWrite(String s)
    {
        logAndWrite(s, false);
    }
    
    private void logAndWrite(String s, boolean error)
    {
        if(!quietToken.getValue())
        {
            if(error)
                System.err.println(s);
            else
                System.out.println(s);
        }
        
        if(error)
            failure(s);
        else
            info(s);
    }
    
    private void readControlFile(String filename)
        throws DbIoException
    {
        String exp = EnvExpander.expand(filename);
        File ctrlfile = new File(exp);
        if (!ctrlfile.canRead())
        {
            System.err.println("Cannot read control file '" + exp 
                + "' -- check name and permissions.");
            System.exit(1);
        }
        try
        {
            LineNumberReader lnr = new LineNumberReader(new FileReader(ctrlfile));
            String line;
            while ((line = lnr.readLine()) != null)
            {
                line = line.trim();
                if (line.length() == 0 || line.charAt(0) == '#')
                    continue;

                if (TextUtil.startsWithIgnoreCase(line, "tsid "))
                    loadTsid(line.substring(5).trim());
                else if (TextUtil.startsWithIgnoreCase(line, "group "))
                    loadGroup(line.substring(6).trim());
                else if (TextUtil.startsWithIgnoreCase(line, "comp "))
                    loadComp(line.substring(5).trim());
                else if (TextUtil.startsWithIgnoreCase(line, "since "))
                {
                    try { since = sdf.parse(line.substring(6)); }
                    catch(ParseException ex)
                    {
                        System.err.println("Invalid Since on line " + lnr.getLineNumber()
                            + " '" + sinceToken.getValue()
                            + "' -- format must be '" + dateSpec + " UTC");
                        System.exit(1);
                    }
                }
                else if (TextUtil.startsWithIgnoreCase(line, "until "))
                {
                    try { until = sdf.parse(line.substring(6)); }
                    catch(ParseException ex)
                    {
                        System.err.println("Invalid Until on line " + lnr.getLineNumber()
                            + " '" + sinceToken.getValue()
                            + "' -- format must be '" + dateSpec + " UTC");
                        System.exit(1);
                    }
                }
            }
            lnr.close();
        }
        catch (IOException ex)
        {
            System.err.println("Error reading control file '" + exp + "': " + ex);
            System.exit(1);
        }
    }

    /**
     * Parse the TSID and add it to the list.
     * @param tsidStr
     * @throws DbIoException
     */
    private void loadTsid(String tsidStr)
        throws DbIoException
    {
        try
        {
            tsids.add(timeSeriesDAO.getTimeSeriesIdentifier(tsidStr));
        }
        catch (NoSuchObjectException ex)
        {
            System.err.println("Invalid TSID '" + tsidStr + "': " + ex);
            System.exit(1);
        }
    }

    /**
     * Load the specified group, expand it, and add the TSIDs to the list.
     * @param nameOrId can be unique group name or ID
     */
    private void loadGroup(String nameOrId)
        throws DbIoException
    {
        TsGroup tsGroup = null;
        try 
        {
            DbKey groupId = DbKey.createDbKey(Long.parseLong(nameOrId.trim()));
            tsGroup = tsGroupDAO.getTsGroupById(groupId);
        }
        catch(Exception ex)
        {
            tsGroup = tsGroupDAO.getTsGroupByName(nameOrId.trim());
        }
        if (tsGroup == null)
        {
            System.err.println("No matching group ID or name for '" + nameOrId + "'");
            System.exit(1);;
        }

        GroupHelper groupHelper = theDb.makeGroupHelper();
        groupHelper.expandTsGroup(tsGroup);
        tsids.addAll(tsGroup.getExpandedList());
    }

    private void loadComp(String nameOrId)
        throws DbIoException
    {
        DbComputation comp = null;
        try 
        {
            DbKey compId = DbKey.createDbKey(Long.parseLong(nameOrId.trim()));
            comp = computationDAO.getComputationById(compId);
        }
        catch(Exception ex)
        {
            try
            {
                comp = computationDAO.getComputationByName(nameOrId.trim());
            }
            catch (NoSuchObjectException ex2)
            {
                System.err.println("No matching computation ID or name for '" + nameOrId + "'");
                System.exit(1);;
            }
        }
        specifiedComps.add(comp);
        logAndWrite("Found computation: " + comp.getUniqueName());
    }

    public void outputTimeSeries(RawMessage rawMsg, Collection<CTimeSeries> ctss)
        throws OutputFormatterException, IOException,
        DataConsumerException, UnknownPlatformException
    {
        DecodedMessage decmsg = new DecodedMessage(rawMsg);
        for(CTimeSeries cts : ctss)
        {
            TimeSeries ts = TSUtil.convert2DecodesTimeSeries(cts);
            Sensor sensor = ts.getSensor();
            boolean toAdd = true;
            if (presGrp != null)
            {
                DataPresentation dp = presGrp.findDataPresentation(sensor);
                if (dp != null)
                {
                    if (dp.getUnitsAbbr() != null
                     && dp.getUnitsAbbr().equalsIgnoreCase("omit"))
                    {
                        OpenDcsLoggerFactory.getLogger().debug(
                            "Omitting sensor '" + sensor.getName() 
                            + "' as per Presentation Group.");
                        toAdd = false;
                    }
                    else
                        ts.formatSamples(dp);
                }
            }
            if (toAdd)
                decmsg.addTimeSeries(ts);
        }

        PipeConsumer pipeConsumer = new PipeConsumer();
        pipeConsumer.open("", new Properties());
        outputFormatter.formatMessage(decmsg, pipeConsumer);
    }

    /** Called with an enabled computation */
    public void evalComp(DbComputation comp)
    {
        info("Evaluating dependencies for comp " + comp.getId() + " " + comp.getName());
        if (!doingFullEval)
            toAdd.clear();

        if (comp.isEnabled() && comp.getProperty("timedCompInterval") == null)
        {
            info("comp is enabled for appID=" + comp.getAppId());
            // If not a group comp just add the completely-specified parms.
            TsGroup grp = null;
            if (!DbKey.isNull(comp.getGroupId()))
            {
                TsGroupDAI tsGroupDAO = theDb.makeTsGroupDAO();
                try
                {
                    grp = tsGroupDAO.getTsGroupById(comp.getGroupId());
                }
                catch (DbIoException ex)
                {
                    warning("Computation ID=" + comp.getId() + " '" + comp.getName()
                        + "' uses invalid groupID=" + comp.getGroupId() + ". Ignoring.");
                    return;
                }
                finally
                {
                    tsGroupDAO.close();
                }
            }
            if (grp == null)
            {
                info("NOT a group comp");
                for(Iterator<DbCompParm> parmit = comp.getParms();
                    parmit.hasNext(); )
                {
                    DbCompParm parm = parmit.next();
                    if (!parm.isInput())
                        continue;
                    // short-cut: for CWMS, the SDI in the parm _is_
                    // the time-series ID. so we don't have to look it up.
                    DbKey tsKey = Constants.undefinedId;
                    DataType dt = parm.getDataType();
                    info("Checking input parm " + parm.getRoleName()
                        + " sdi=" + parm.getSiteDataTypeId() + " intv=" + parm.getInterval()
                        + " tabsel=" + parm.getTableSelector() + " modelId=" + parm.getModelId()
                        + " dt=" + (dt==null?"null":dt.getCode()) + " siteId=" + parm.getSiteId()
                        + " siteName=" + parm.getSiteName());
                    if (theDb.isHdb())
                    {
                        // For HDB, the SDI is not the same as time series key.
                        TimeSeriesIdentifier tmpTsid = new HdbTsId();
                        theDb.transformUniqueString(tmpTsid, parm);
                        info("After transform, param ID='" + tmpTsid.getUniqueString() + "'");
                        TimeSeriesIdentifier tsid = timeSeriesDAO.getCache().getByUniqueName(
                            tmpTsid.getUniqueString());
                        if (tsid != null)
                        {
                            tsKey = tsid.getKey();
                            info("From cache, this is TS_IS=" + tsKey);
                        }
                        else
                            info("No such time-series in the cache.");
                    }
                    else
                        tsKey = parm.getSiteDataTypeId();
                    if (!tsKey.isNull())
                        addCompDepends(tsKey, comp.getId());
                }
            }
            else // it is a group computation
            {
                // The cached version may have been too old and a fresh copy read from the DB.
                // If so, it needs to be expanded before use.
                if (!grp.getIsExpanded())
                {
                    try { groupHelper.expandTsGroup(grp); }
                    catch(DbIoException ex)
                    {
                        failure("Cannot evaluate group ID=" + grp.getKey() + " '" + grp.getGroupName()
                            + "': " + ex);
                        failure("...Therefore cannot evaluation computation '" + comp.getName() + "'");
                        return;
                    }
                }
                info("IS a group comp with group " + grp.getGroupId() + " " + grp.getGroupName()
                    + " numExpandedMembers: " + grp.getExpandedList().size());

                // For each time series in the expanded list
                for(TimeSeriesIdentifier tsid : grp.getExpandedList())
                {
                    OpenDcsLoggerFactory.getLogger().debug("Checking group tsid=" + tsid.getUniqueString());
                    // for each input parm
                    for(Iterator<DbCompParm> parmit = comp.getParms();
                            parmit.hasNext(); )
                    {
                        DbCompParm parm = parmit.next();
                        OpenDcsLoggerFactory.getLogger().debug("  parm '" + parm.getRoleName() + "'");
                        if (!parm.isInput())
                        {
                            OpenDcsLoggerFactory.getLogger().debug("     - Not an input. Skipping.");
                            continue;
                        }
                        // Transform the group TSID by the parm
                        OpenDcsLoggerFactory.getLogger().debug("Checking input parm " + parm.getRoleName()
                            + " sdi=" + parm.getSiteDataTypeId() + " intv=" + parm.getInterval()
                            + " tabsel=" + parm.getTableSelector() + " modelId=" + parm.getModelId()
                            + " dt=" + parm.getDataType() + " siteId=" + parm.getSiteId()
                            + " siteName=" + parm.getSiteName());
                        TimeSeriesIdentifier tmpTsid = tsid.copyNoKey();
                        OpenDcsLoggerFactory.getLogger().debug("Triggering ts=" + tmpTsid.getUniqueString());
                        theDb.transformUniqueString(tmpTsid, parm);
                        OpenDcsLoggerFactory.getLogger().debug("After transform, param ID='" + tmpTsid.getUniqueString() + "'");
                        TimeSeriesIdentifier parmTsid = 
                            timeSeriesDAO.getCache().getByUniqueName(tmpTsid.getUniqueString());
                        // If the transformed TSID exists, it is a dependency.
                        if (parmTsid != null)
                            addCompDepends(parmTsid.getKey(), comp.getId());
                        else
                            OpenDcsLoggerFactory.getLogger().debug("TS " + tmpTsid.getUniqueString() + " not in cache.");
                    }
                }
            }
        }
        if (!doingFullEval)
        {
            try { writeToAdd2Db(comp.getId()); }
            catch(DbIoException ex) { /* do nothing -- err msg already logged. */ }
        }
    }
    
    protected void writeToAdd2Db(DbKey compId2Delete)
        throws DbIoException
    {
        if (toAdd.size() == 0)
            return;

        // Clear the scratchpad
        String q = "DELETE FROM CP_COMP_DEPENDS_SCRATCHPAD";
        try
        {
            info(q);
            compDependsDAO.doModify(q);

            // Insert all the toAdd records into the scratchpad
            for(CpCompDependsRecord ccd : toAdd)
            {
                q = "INSERT INTO CP_COMP_DEPENDS_SCRATCHPAD(TS_ID, COMPUTATION_ID)"
                    + " VALUES(" + ccd.getTsKey() + ", " + ccd.getCompId() + ")";
                info(q);
                compDependsDAO.doModify(q);
            }

            //TODO - Ideally, the delete and insert should be done as a transaction.

            if (compId2Delete != Constants.undefinedId)
            {
                q = "DELETE FROM CP_COMP_DEPENDS WHERE COMPUTATION_ID = " + compId2Delete;
                info(q);
                compDependsDAO.doModify(q);
            }

            // Just in case, delete any records from the scratchpad that are already
            // in compdepends:
            q = "delete from cp_comp_depends_scratchpad sp "
                + "where exists(select * from cp_comp_depends cd where cd.computation_id = sp.computation_id "
                + "and cd.ts_id = sp.ts_id)";
            compDependsDAO.doModify(q);

            // Copy the scratchpad to the cp_comp_depends table
            q = "INSERT INTO CP_COMP_DEPENDS SELECT * FROM CP_COMP_DEPENDS_SCRATCHPAD";
            info(q);
            compDependsDAO.doModify(q);

            // Finally, clear the scratchpad, otherwise this can leave a foreign key to TS_ID
            // that may prevent time series from being deleted.
            q = "delete from cp_comp_depends_scratchpad";
            compDependsDAO.doModify(q);

//			if (compId2Delete != Constants.undefinedId)
//				theDb.commit(); // This should terminate the transaction.

            // Now, since we deleted the deps at the start of the operation,
            // even if the dependency existed before treat it as a new dependency.
            // Enqueue all data for the time-series back to the notify time
            // as tasklist records.
            //			createTaskListRecordsFor(toAdd);
            // MJM: We discovered that creating tasklist records takes a very long
            // time since we have to query r_instant (and other tables) by date_time_loaded
            // and there is no index on date_time_loaded. Each time series was talking
            // well over a minute to do the query. So punt for now.
        }
        catch (DbIoException ex)
        {
            warning("Error in query '" + q + "': " + ex);
            throw ex;
        }
    }

    protected void addCompDepends(DbKey tsKey, DbKey compId)
    {
        CpCompDependsRecord rec = new CpCompDependsRecord(tsKey, compId);
        info("addCompDepends(" + tsKey + ", " + compId + ") before, toAdd.size=" + toAdd.size());
        toAdd.add(rec);
    }
}
