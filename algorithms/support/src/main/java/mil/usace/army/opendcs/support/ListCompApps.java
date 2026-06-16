package mil.usace.army.opendcs.support;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;

import opendcs.dai.LoadingAppDAI;
import org.opendcs.utils.logging.OpenDcsLoggerFactory;
import org.slf4j.Logger;

import decodes.tsdb.CompAppInfo;
import decodes.tsdb.TsdbAppTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Renders some basic information about computation apps,
 * such as the name, enabled, and properties to standard out.
 * 
 * Currently the primary use case is for USACE's cloud system
 * however, it may be useful to others.
 */
public final class ListCompApps extends TsdbAppTemplate
{
    private static Logger log = OpenDcsLoggerFactory.getLogger();

    public ListCompApps()
    {
        super(null);
    }

    @Override
    public void runApp()
    {
        try (LoadingAppDAI compDao = theDb.makeLoadingAppDAO())
        { 
            List<ApiLoadingApp> apps = compDao.listComputationApps(false)
                                              .stream()
                                              .map(cai -> mapLoading(cai))
                                              .collect(Collectors.toList());
            ObjectMapper objMap = new ObjectMapper();
            objMap.registerModule(new Jdk8Module());
            objMap.registerModule(new JavaTimeModule());
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'[z]");
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            objMap.setDateFormat(sdf);
            System.out.println(objMap.writeValueAsString(apps));
        }
        catch (Exception ex)
        {
            log.atError().setCause(ex).log("Unasble to fully process extraction of computation app information.");
        }
    }

    static ApiLoadingApp mapLoading(CompAppInfo app)
	{
		ApiLoadingApp ret = new ApiLoadingApp();
		ret.setAppId(app.getAppId().getValue());
		ret.setAppName(app.getAppName());
		ret.setComment(app.getComment());
		ret.setLastModified(app.getLastModified());
		ret.setManualEditingApp(app.getManualEditApp());
		ret.setAppType(app.getAppType());
		ret.setProperties(app.getProperties());
		return ret;
	}
    
    public static void main(String[] args) throws Exception
    {
        ListCompApps app = new ListCompApps();
        app.execute(args);
        
    }

}