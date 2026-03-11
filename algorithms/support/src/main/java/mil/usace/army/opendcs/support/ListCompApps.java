/*
* Where Applicable, Copyright 2025 OpenDCS Consortium and/or its contributors
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy
* of the License at
*
*   http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations
* under the License.
*/
package mil.usace.army.opendcs.support;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;

import opendcs.dai.LoadingAppDAI;
import org.opendcs.database.DatabaseService;
import org.opendcs.database.api.OpenDcsDatabase;
import org.opendcs.utils.logging.OpenDcsLoggerFactory;
import org.slf4j.Logger;

import decodes.db.DatabaseException;
import decodes.tsdb.TsdbAppTemplate;
import decodes.util.CmdLineArgs;
import decodes.util.DecodesSettings;


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
            List<ApiLoadingApp> apps = compDao.listComputationApps(true)
                                              .stream()
                                              .map(mapLoading)
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
    
    public static void main(String[] args)
    {
        ListCompApps app = new ListCompApps();
        app.execute(args);
        
    }

}