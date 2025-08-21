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
	String host_type2 = "stg"
    String key2 = host_type2 + "-" + field_name;
    String value2 = map.get(key2);
    return value2;
}

def log = Logger.getLogger("com.acme.setApprover")
log.setLevel(Level.INFO)

def CFM = ComponentAccessor.getCustomFieldManager()
def userManager = ComponentAccessor.getUserManager()
def optionsManager = ComponentAccessor.getOptionsManager()

boolean needReason = false

