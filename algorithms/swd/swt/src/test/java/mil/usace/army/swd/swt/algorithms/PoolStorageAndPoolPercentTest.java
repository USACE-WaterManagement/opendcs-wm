package mil.usace.army.swd.swt.algorithms;

import org.junit.jupiter.api.Test;

import mil.usace.army.swd.swt.algorithms.PoolStorageAndPoolPercent.NeededLevels;

import static org.junit.jupiter.api.Assertions.*;

public class PoolStorageAndPoolPercentTest
{

    @Test
    void test_xml_parsing() throws Exception
    {

        final String testData = "<pool attribute=\"1\" suffix=\"Conservation\">\n" + //
                        "    <loc_ref base_location_id=\"ALTU\" sub_location_id=\"\">\n" + //
                        "        <db_office_id id=\"SWT\" />\n" + //
                        "    </loc_ref>\n" + //
                        "    <min_loc_level_ref>\n" + //
                        "        <specified_level id=\"Top of Dead Storage\">\n" + //
                        "            <db_office_id id=\"SWT\" />\n" + //
                        "            <description />\n" + //
                        "        </specified_level>\n" + //
                        "        <loc_ref base_location_id=\"ALTU\" sub_location_id=\"\">\n" + //
                        "            <db_office_id id=\"SWT\" />\n" + //
                        "        </loc_ref>\n" + //
                        "        <parameter id=\"Elev\" />\n" + //
                        "        <parameter_type id=\"Inst\" />\n" + //
                        "        <duration id=\"0\" />\n" + //
                        "    </min_loc_level_ref>\n" + //
                        "    <max_loc_level_ref>\n" + //
                        "        <specified_level id=\"Top of Conservation\">\n" + //
                        "            <db_office_id id=\"SWT\" />\n" + //
                        "            <description />\n" + //
                        "        </specified_level>\n" + //
                        "        <loc_ref base_location_id=\"ALTU\" sub_location_id=\"\">\n" + //
                        "            <db_office_id id=\"SWT\" />\n" + //
                        "        </loc_ref>\n" + //
                        "        <parameter id=\"Elev\" />\n" + //
                        "        <parameter_type id=\"Inst\" />\n" + //
                        "        <duration id=\"0\" />\n" + //
                        "    </max_loc_level_ref>\n" + //
                        "    <comment>Conservation Pool</comment>\n" + //
                        "</pool>";
        NeededLevels levels = NeededLevels.fromXml("test", testData);
        assertEquals("test.Elev.Inst.0.Top of Dead Storage", levels.minLevelId);
        assertEquals("test.Elev.Inst.0.Top of Conservation", levels.maxLevelId);
    }
}
