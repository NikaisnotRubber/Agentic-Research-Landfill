import org.apache.log4j.Logger
import org.apache.log4j.Level

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Map;
import java.util.HashMap
import java.util.List;
import java.util.ArrayList;

import com.atlassian.jira.issue.Issue
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.issue.customfields.option.Option
import com.atlassian.jira.issue.customfields.option.LazyLoadedOption

String getHostType() {
    String rc = null;	
    def baseurl = com.atlassian.jira.component.ComponentAccessor.getApplicationProperties().getString("jira.baseurl")
	if (baseurl.indexOf("jiradev") != -1) {
		rc = "dev";
	}

	if (baseurl.indexOf("jirastage") != -1) {
		rc = "stage";
	}

	if (baseurl.indexOf("jiradelta") != -1) {
		rc = "prod";
	}	
	return rc;
}

String getValue(HashMap<String, String> map, String host_type, String field_name) {
	// 因為在 prod 環境, 直接指定 prod, 2024-03-12
	String host_type2 = "prod"
    String key2 = host_type2 + "-" + field_name;
    String value2 = map.get(key2);
    return value2;
}

def log = Logger.getLogger("com.acme.setApprover")
log.setLevel(Level.INFO)

def CFM = ComponentAccessor.getCustomFieldManager()
def userManager = ComponentAccessor.getUserManager()
def optionsManager = ComponentAccessor.getOptionsManager()

def host_type = getHostType()
log.info (" host_type : " + host_type)
HashMap<String, String> map = new HashMap<>();

map.put("stage-Team Resource Involved","customfield_15753") // Team Resource Involved
map.put("stage-Team Resource in Region", "customfield_19119") // Team Resource in Region
map.put("stage-Corp IT", "customfield_19132") // Corp IT
map.put("stage-CN IT", "customfield_19133") // CN IT
map.put("stage-AM IT", "customfield_19134") // AM IT
map.put("stage-SG IT", "customfield_19135") // SG IT
map.put("stage-NEA IT", "customfield_19136") // NEA IT
map.put("stage-DET IT", "customfield_19137") // DET IT
map.put("stage-EMEA IT", "customfield_19138") // EMEA IT
map.put("stage-DIN IT", "customfield_19139") // DIN IT

map.put("prod-Team Resource Involved","customfield_15753") // Team Resource Involved
map.put("prod-Team Resource in Region", "customfield_19211") // Team Resource in Region
map.put("prod-Corp IT", "customfield_19212") // Corp IT
map.put("prod-CN IT", "customfield_19213") // CN IT
map.put("prod-AM IT", "customfield_19214") // AM IT
map.put("prod-SG IT", "customfield_19215") // SG IT
map.put("prod-NEA IT", "customfield_19216") // NEA IT
map.put("prod-DET IT", "customfield_19217") // DET IT
map.put("prod-EMEA IT", "customfield_19218") // EMEA IT
map.put("prod-DIN IT", "customfield_19219") // DIN IT

// Team Resource Involved
String value1 = getValue(map, host_type, "Team Resource Involved");
def obj_team_inv = CFM.getCustomFieldObject(value1);
def obj_team_inv_option = optionsManager.getOptions(obj_team_inv.getRelevantConfig(issue))
// String val_team_inv = issue.getCustomFieldValue(obj_team_inv) as String

def fieldConfig = obj_team_inv.getRelevantConfig(issue) 
def options = optionsManager.getOptions(fieldConfig)

// Team Resource in Region
String value2 = getValue(map, host_type, "Team Resource in Region");
def obj_team_reg = CFM.getCustomFieldObject(value2);
def val_team_reg = issue.getCustomFieldValue(obj_team_reg) 

// DIN IT
String value3 = getValue(map, host_type, "DIN IT");
def obj_din_it = CFM.getCustomFieldObject(value3);
def val_din_it = issue.getCustomFieldValue(obj_din_it) 

// NEA IT
String value4 = getValue(map, host_type, "NEA IT");
def obj_nea_it = CFM.getCustomFieldObject(value4);
def val_nea_it = issue.getCustomFieldValue(obj_nea_it) 

// Corp IT
String value5 = getValue(map, host_type, "Corp IT");
def obj_corp_it = CFM.getCustomFieldObject(value5);
def val_corp_it = issue.getCustomFieldValue(obj_corp_it) 

// CN IT
String value6 = getValue(map, host_type, "CN IT");
def obj_cn_it = CFM.getCustomFieldObject(value6);
def val_cn_it = issue.getCustomFieldValue(obj_cn_it) 

// AM IT
String value7 = getValue(map, host_type, "AM IT");
def obj_am_it = CFM.getCustomFieldObject(value7);
def val_am_it = issue.getCustomFieldValue(obj_am_it) 

// SG IT
String value8 = getValue(map, host_type, "SG IT");
def obj_sg_it = CFM.getCustomFieldObject(value8);
def val_sg_it = issue.getCustomFieldValue(obj_sg_it) 

// DET IT
String value9 = getValue(map, host_type, "DET IT");
def obj_det_it = CFM.getCustomFieldObject(value9);
def val_det_it = issue.getCustomFieldValue(obj_det_it) 

// EMEA IT
String value10 = getValue(map, host_type, "EMEA IT");
def obj_emea_it = CFM.getCustomFieldObject(value10);
def val_emea_it = issue.getCustomFieldValue(obj_emea_it) 

def chosenValuesList = []
// def chosenValuesList2 = []

if (val_team_reg in String) {
    chosenValuesList.add(val_team_reg)
} else if (val_team_reg in ArrayList) {
    chosenValuesList.addAll(val_team_reg)
}

boolean isCorpIT = false;
boolean isCNIT = false;
boolean isAMIT = false;
boolean isSGIT = false;
boolean isNEAIT = false;
boolean isDETIT = false;
boolean isEMEAIT = false;
boolean isDINIT = false;

for (String str : chosenValuesList) {
    log.info (" chosenValuesList : " + str)
    switch (str) {
        case "Corp IT Teams":
            isCorpIT = true;
            break;
        case "CN IT Teams":
            isCNIT = true;
            break;    
        case "AM IT Teams":
            isAMIT = true;
            break;       
        case "SG IT Teams":
            isSGIT = true;
            break;    
        case "NEA IT Teams":
            isNEAIT = true;
            break;    
        case "DET IT Teams":
            isDETIT = true;
            break;    
        case "EMEA IT Teams":
            isEMEAIT = true;
            break;    
        case "DIN IT Teams":
            isDINIT = true;
            break;      
        default:
            break;  
    }
}

def list1 = []
// def list2 = []
List<String> list2 = new ArrayList<>();

// isDINIT
if (isDINIT) {
    log.info (" isDINIT : " + isDINIT)
    if (val_din_it in String) {
        list1.add(val_din_it)
    } else if (val_din_it in ArrayList) {
        list1.addAll(val_din_it)
    }    
}

if (isNEAIT) {
    log.info (" isNEAIT : " + isNEAIT)
    if (val_nea_it in String) {
        list1.add(val_nea_it)
    } else if (val_nea_it in ArrayList) {
        list1.addAll(val_nea_it)
    }    
}

if (isCorpIT) {
    log.info (" isCorpIT : " + isCorpIT)
    if (val_corp_it in String) {
        list1.add(val_corp_it)
    } else if (val_corp_it in ArrayList) {
        list1.addAll(val_corp_it)
    }    
}

if (isCNIT) {
    log.info (" isCNIT : " + isCNIT)
    if (val_cn_it in String) {
        list1.add(val_cn_it)
    } else if (val_cn_it in ArrayList) {
        list1.addAll(val_cn_it)
    }    
}

if (isAMIT) {
    log.info (" isAMIT : " + isAMIT)
    if (val_am_it in String) {
        list1.add(val_am_it)
    } else if (val_am_it in ArrayList) {
        list1.addAll(val_am_it)
    }    
}

if (isSGIT) {
    log.info (" isSGIT : " + isSGIT)
    if (val_sg_it in String) {
        list1.add(val_sg_it)
    } else if (val_sg_it in ArrayList) {
        list1.addAll(val_sg_it)
    }    
}

if (isDETIT) {
    log.info (" isDETIT : " + isDETIT)
    if (val_det_it in String) {
        list1.add(val_det_it)
    } else if (val_det_it in ArrayList) {
        list1.addAll(val_det_it)
    }    
}

if (isEMEAIT) {
    log.info (" isEMEAIT : " + isEMEAIT)
    if (val_emea_it in String) {
        list1.add(val_emea_it)
    } else if (val_emea_it in ArrayList) {
        list1.addAll(val_emea_it)
    }    
}

for (String st1 : list1)
    log.info (" val_din_it : " + st1)

for (Option opt : obj_team_inv_option) 
    log.info (" team_inv : " + opt.getValue() + "<<<")

for (Option opt : obj_team_inv_option) {
    for (String st1 : list1) {
        if (opt.getValue().equalsIgnoreCase(st1)) {
            log.info (" st1 : " + st1 + " added !!!")
            list2.add(opt)    
        }
    }
}

// 避免清掉的問題, 2023-11-14
if (list2.size() > 0) 
    issue.setCustomFieldValue(obj_team_inv, list2)
