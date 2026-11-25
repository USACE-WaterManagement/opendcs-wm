/*
 * Opens source software by Cove Software, LLC.
 */
package decodes.datasource;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Properties;
import java.util.Vector;

import org.opendcs.utils.logging.OpenDcsLoggerFactory;

import ilex.util.IDateFormat;
import ilex.util.PropertiesUtil;
import ilex.util.TextUtil;
import decodes.Constants;
import decodes.db.ConfigSensor;
import decodes.db.DataSource;
import decodes.db.DataType;
import decodes.db.Database;
import decodes.db.DatabaseException;
import decodes.db.InvalidDatabaseException;
import decodes.db.NetworkList;
import decodes.db.NetworkListEntry;
import decodes.db.Platform;
import decodes.db.PlatformSensor;
import decodes.db.Site;
import decodes.db.SiteName;
import decodes.util.PropertySpec;


/**
 * This class is modeled on the generic WebAbstractDataSource. It is designed
 * specifically for CDEC data.
 * 
 * Here is an example URL:
 * <a href="https://cdec.water.ca.gov/dynamicapp/req/CSVDataServlet?Stations=DLV&SensorNums=6,15&dur_code=H&Start=2025-07-01&End=2025-07-03">CDEC Link</a>
 * 
 * The "Stations" argument provides a CDEC Site key. The data source gets the platform record from
 * the network list, and then the site record from the platform record, and the CDEC site name from the
 * site record.
 * 
 * startDT and endDT are taken from the since/until time provided to the routing spec. If until
 * is missing (i.e. real-time), then it is set to now. This DataSource cannot be a real-time data
 * source, it is intended to run periodically on a schedule.
 * 
 * SensorNums is a comma-separated list of CDEC sensors. The data source takes this from the
 * Configuration record associated with the platform. The list will include all sensors that have
 * a CDEC data type assigned. Any sensors that have a property "omit" set to "true" will be skipped.
 * 
 * Properties:
 * 	baseUrl - The base URL for CDEC data.
 *  dataTypeStandard - default="cdec". This determines which sensor data types to include in the URL.
 */
public class CdecWebDataSource
	extends DataSourceExec
{
	private String module = "CdecWebDataSource";
	
	// aggregate list of IDs from all network lists.
	private ArrayList<String> aggIds = new ArrayList<String>();
	
	// And aggregate list of Platforms corresponding to the IDs above
	private ArrayList<Platform> platforms = new ArrayList<Platform>();
	
	// retrieved from property
	private String baseUrl = 
		"https://cdec.water.ca.gov/dynamicapp/req/CSVDataServlet?";

	private String dataTypeStandard = "cdec";
	
	private Properties myProps = new Properties();
	
	Date dSince = null, dUntil = null;
	
	private WebDataSource currentWebDs = null;
	private int xportIdx = 0;
	private int urlsGenerated = 0;
	private String currentMediumId = null;
	private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm");
	
	private static final PropertySpec[] UTprops =
	{
		new PropertySpec("baseUrl", PropertySpec.STRING, 
			"Base URL to the CDEC API"),
		new PropertySpec("dataTypeStandard", PropertySpec.DECODES_ENUM + Constants.enum_DataTypeStd,
			"To select which sensor data type to use in the URL, default=cdec")
	};

	/**
	 * @see decodes.datasource.DataSourceExec#DataSourceExec(DataSource, Database) DataSourceExec Constructor
	 *
	 * @param ds
	 * @param db
	 */
	public CdecWebDataSource(DataSource ds, Database db)
	{
		super(ds,db);
	}

	/**
	 * Re-evaluate the abstract URL with the next medium ID in the aggregate list.
	 */
	private String buildNextWebAddr()
		throws DataSourceException
	{
		// Processed all DCPs in the netlists and at least one URL was generated.
		if (xportIdx >= aggIds.size())
			return null;
		
		currentMediumId = aggIds.get(xportIdx);
		Platform p = platforms.get(xportIdx);
		xportIdx++;
		
		if (p == null)
		{
			OpenDcsLoggerFactory.getLogger().warn(module + " No platform for transport ID '" 
				+ currentMediumId + "' -- skipped.");
			return buildNextWebAddr();
		}
		
		String siteNum = currentMediumId;
		StringBuilder sb = new StringBuilder();
		sb.append(baseUrl);
		
		// Add the CDEC site ID.
		sb.append("Stations=" + siteNum + "&");

		// Add time range
		sb.append("Start=" + sdf.format(dSince) + "&End=" + sdf.format(dUntil));
		
		// add list of comma-separated CDEC data type codes
		sb.append("&SensorNums=");
		int numElements = 0;
		for(ConfigSensor cs : p.getConfig().getSensorVec())
		{
			int sensNum = cs.sensorNumber;
			PlatformSensor ps = p.getPlatformSensor(sensNum);

			// Skip sensor if omit property == true
			String s = cs.getProperty("omit");
			if (ps != null && ps.getProperty("omit") != null)
				s = ps.getProperty("omit");
			if (TextUtil.str2boolean(s))
			{
				OpenDcsLoggerFactory.getLogger().debug(module + " omit=true for sensor " + sensNum);
				continue;
			}
			
			DataType dt = cs.getDataType(dataTypeStandard);
			if (dt == null)
			{
				if (dataTypeStandard.equalsIgnoreCase(Constants.datatype_CDEC))
					dt = cs.getDataType(Constants.datatype_CDEC);
				if (dt == null)
				{
					OpenDcsLoggerFactory.getLogger().info(module + " trans id '" + currentMediumId 
						+ "' sensor " + sensNum
						+ " has no " + dataTypeStandard + " data type -- skipping.");
					continue;
				}
			}
			if (numElements++ > 0)
				sb.append(",");
			sb.append(dt.getCode());
		}
		
		urlsGenerated++;
		return sb.toString();
	}

	@Override
	public void processDataSource()
	{
		PropertiesUtil.copyProps(myProps, getDataSource().getArguments());
	}

	@Override
	public void init(Properties rsProps, String since, 
			String until, Vector<NetworkList> netlists) 
		throws DataSourceException
	{
		OpenDcsLoggerFactory.getLogger().info(module + " initializing ...");
		PropertiesUtil.copyProps(myProps, rsProps);
		
		if (routingSpecThread.getRoutingSpec().outputTimeZone != null)
			sdf.setTimeZone(routingSpecThread.getRoutingSpec().outputTimeZone);

		String s = PropertiesUtil.getIgnoreCase(myProps, "baseUrl");
		if (s != null)
			baseUrl = s;
		
		s = PropertiesUtil.getIgnoreCase(myProps, "dataTypeStandard");
		if (s != null)
			dataTypeStandard = s;
		
		// Default since time to 1 day.
		dSince = since != null ? IDateFormat.parse(since) : 
			new Date(System.currentTimeMillis() - 3600000L * 24);

		dUntil = until != null ? IDateFormat.parse(until) : new Date();
		OpenDcsLoggerFactory.getLogger().info(module + " since=" + dSince + ", until=" + dUntil);
		
		aggIds.clear();
		platforms.clear();
		if (netlists != null)
			for(NetworkList nl : netlists)
			{
				for (NetworkListEntry nle : nl.values())
					if (!aggIds.contains(nle.getTransportId()))
					{
						String tid = nle.getTransportId();
						// Same ID might be in multiple lists. Guard against dups.
						aggIds.add(tid);
						try
						{
							// will be null placeholder if platform doesn't exist in the db.
							platforms.add(nl.getDatabase().platformList.getPlatform(
								nl.transportMediumType, tid));
						}
						catch (DatabaseException e)
						{
							String msg = "Cannot search database for platform '" + tid + "': " + e;
							OpenDcsLoggerFactory.getLogger().warn(module + " " + msg);
							throw new DataSourceException(msg);
						}
					}
			}
		
		if (aggIds.size() == 0)
		{
			String msg = module + " init() No medium ids.";
			OpenDcsLoggerFactory.getLogger().warn(msg);
			throw new DataSourceException(msg);
		}
		xportIdx = 0;
		urlsGenerated = 0;

		// Make the web data source that will do the actual IO. In the getRawMessage
		// loop we re-initialize it for each URL that we build.
		try
		{
			DataSource dsrec = new DataSource("absWebReader", "web");
			currentWebDs = (WebDataSource)dsrec.makeDelegate();
			currentWebDs.processDataSource();
			currentWebDs.setAllowNullPlatform(this.getAllowNullPlatform());
		}
		catch(InvalidDatabaseException ex) 
		{
			OpenDcsLoggerFactory.getLogger().info(module + " " + ex);
			throw new DataSourceException(module + " " + ex);
		}
	}
	
	@Override
	public void close()
	{
		if (currentWebDs != null)
			currentWebDs.close();
		currentWebDs = null;
	}

	@Override
	public RawMessage getRawMessage() 
		throws DataSourceException
	{
		if (currentWebDs.isOpen())
		{
			try { return currentWebDs.getRawMessage(); }
			catch(DataSourceEndException ex)
			{
				OpenDcsLoggerFactory.getLogger().info(module
					+ " end of '" + currentWebDs.getActiveSource() + "'");
			}
		}

		String url;
		while((url = buildNextWebAddr()) != null)
		{
			OpenDcsLoggerFactory.getLogger().debug(module + " next url '" + url + "'");
			myProps.setProperty("url", url);
			myProps.setProperty("mediumid", currentMediumId);
			try
			{
				currentWebDs.init(myProps, "", "", null);
				RawMessage ret = currentWebDs.getRawMessage();
				return ret;
			}
			catch(DataSourceException ex)
			{
				String msg = module + " cannot open '"
					+ url + "': " + ex;
				OpenDcsLoggerFactory.getLogger().warn(msg);
			}
			catch(Exception ex)
			{
				String msg = module + " cannot open '"
					+ url + "': " + ex;
				OpenDcsLoggerFactory.getLogger().warn(msg);
				System.err.println(msg);
				ex.printStackTrace(System.err);
			}
		}
		// No more medium IDs
		throw new DataSourceEndException(module 
			+ " " + aggIds.size() + " medium IDs processed.");
	}
	
	@Override
	public PropertySpec[] getSupportedProps()
	{
		return PropertiesUtil.combineSpecs(super.getSupportedProps(), 
			PropertiesUtil.combineSpecs(UTprops, StreamDataSource.SDSprops));
	}

	@Override
	public boolean supportsTimeRanges()
	{
		return true;
	}

	@Override
	protected RawMessage getSourceRawMessage() throws DataSourceException {
		// TODO Auto-generated method stub
		throw new UnsupportedOperationException("Unimplemented method 'getSourceRawMessage'");
	}

}
