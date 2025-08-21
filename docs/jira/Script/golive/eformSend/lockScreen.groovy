// Jira SDK
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.comments.CommentManager
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.mail.Email
import com.atlassian.jira.mail.settings.MailSettings
import com.atlassian.mail.MailException
import com.atlassian.mail.queue.SingleMailQueueItem
import com.atlassian.mail.server.SMTPMailServer
//
import groovy.json.JsonSlurper
import groovy.json.StreamingJsonBuilder
import org.apache.log4j.Level
import org.apache.log4j.Logger
import java.text.SimpleDateFormat
import java.util.stream.Collectors
// 
import java.util.ArrayList
import java.util.HashMap
import java.util.List
import java.util.Map
import java.util.Date
import java.util.Calendar
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

// Constants
final String AUTH_HEADER_VALUE = "Basic amlyYXNlcnZpY2UuYXBpOlBhc3N3b3JkMDE="

final String HOST_TYPE_DEV = "dev"
final String HOST_TYPE_STAGE = "stage"
final String HOST_TYPE_PROD = "prod"

final String JIRA_BASE_URL_DEV = "https://jiradev.deltaww.com/"
final String JIRA_BASE_URL_STAGE = "https://jirastage.deltaww.com/"
final String JIRA_BASE_URL_PROD = "https://jiradelta.deltaww.com/"

// Costom Field
final String FIELD_PROJECT_REVIEWER = "Project Reviewer"
final String FIELD_PROJECT_NAME = "Project Name"
final String FIELD_PROJECT_ATTR = "Project Attr"
final String FIELD_PROJECT_AREA = "Project Area"
final String FIELD_PROJECT_DESCRIPTION = "Project Description"
final String FIELD_ITPM = "ITPM"
final String FIELD_TEAM_RESOURCE_REGION = "Team Resource in Region"
final String FIELD_CORP_IT_TEAMS = "Corp IT Teams"
final String FIELD_CN_IT_TEAMS = "CN IT Teams"
final String FIELD_AM_IT_TEAMS = "AM IT Teams"
final String FIELD_SG_IT_TEAMS = "SG IT Teams"
final String FIELD_NEA_IT_TEAMS = "NEA IT Teams"
final String FIELD_DET_IT_TEAMS = "DET IT Teams"
final String FIELD_EMEA_IT_TEAMS = "EMEA IT Teams"
final String FIELD_DIN_IT_TEAMS = "DIN IT Teams"
final String FIELD_CORP_STRATEGY_PROGRAM = "Corp Strategy & Program"
final String FIELD_PLAN_INITIATION_DATE = "Plan Initiation Date"
final String FIELD_PLAN_GO_LIVE_DATE = "Plan Go-live Date"
final String FIELD_PLAN_END_DATE = "Plan End Date"
final String FIELD_PLAN_CLOSURE_DATE = "Plan Closure Date"

// --- Functional Methods ---


// --- Functional Methods ---

// Define the target status name and the fields to lock for this status
final String TARGET_STATUS_NAME = "Go-Live Approval"
final List<String> FIELDS_TO_LOCK = [
    "Request Info",
    "Security Scan",
    "Documents",
    "Data Governance",
    "Security Review"
]

log.info("Attempting to lock fields for issue: ${issue?.key}")

if (issue == null) {
    log.error("Issue object is not available.")
    return // Exit script
}

// Get the target status from transientVars, which is usually available in ScriptRunner post-functions
// The key for the target status might vary depending on ScriptRunner version and configuration.
// Common keys are "status" or "destinationStatus".
// Get the target status name from transientVars, which is usually available in ScriptRunner post-functions.
// The key for the target status might vary depending on ScriptRunner version and configuration.
// Common keys are "status" or "destinationStatus".
// We safely convert the result to a string to avoid "No such property: name" errors.
def targetStatusName = transientVars?.get("status")?.toString() ?: transientVars?.get("destinationStatus")?.toString()

if (targetStatusName == null) {
    log.error("Target status name is not available from transientVars using keys 'status' or 'destinationStatus'.")
    return // Exit script
}

log.info("Retrieved target status name from transientVars: ${targetStatusName}")

// Check if the target status is the one we want to lock fields for
if (targetStatusName == TARGET_STATUS_NAME) {
    log.info("Target status is '${TARGET_STATUS_NAME}'. Proceeding to lock specified fields.")

    def customFieldManager = ComponentAccessor.getCustomFieldManager()

    FIELDS_TO_LOCK.each { fieldName ->
        def customField = customFieldManager.getCustomFieldObjectsByName(fieldName).first()

        if (customField != null) {
            log.info("Attempting to lock field: ${customField.name}")
            // --- ScriptRunner Specific: Locking a field ---
            // The exact method to lock a field programmatically depends heavily on ScriptRunner version and configuration.
            // You need to use the appropriate ScriptRunner API or method available in the post-function script context.
            // Consult your ScriptRunner documentation for the correct way to set a field as read-only.
            // A common approach is to use a helper method provided by ScriptRunner.

            // Example (conceptual - replace with actual ScriptRunner API):
            // try {
            //     // Assuming ScriptRunner provides a method like 'setFieldReadOnly'
            //     // fieldHelper.setFieldReadOnly(issue, customField, true)
            //     log.warn("Programmatic field locking requires specific ScriptRunner API usage. Please consult ScriptRunner documentation.")
            // } catch (Exception e) {
            //     log.error("Error attempting to lock field ${customField.name}: ${e.getMessage()}")
            // }

            // Alternatively, consider using the built-in "Set field as read-only" post-function in your workflow configuration.

        } else {
            log.warn("Custom field '${fieldName}' not found.")
        }
    }
} else {
    log.info("Target status is not '${TARGET_STATUS_NAME}'. No fields to lock for this status.")
}

log.info("Field locking logic executed.")

// --- Main Method -- Listen Event ---

// Check if the target status is the one we want to lock fields for
if (targetStatusName == TARGET_STATUS_NAME) {
    log.info("Target status is '${TARGET_STATUS_NAME}'. Proceeding to lock specified fields.")

    def customFieldManager = ComponentAccessor.getCustomFieldManager()

    FIELDS_TO_LOCK.each { fieldName ->
        def customField = customFieldManager.getCustomFieldObjectsByName(fieldName).first()

        if (customField != null) {
            log.info("Attempting to lock field: ${customField.name}")
        } else {
            log.warn("Custom field '${fieldName}' not found.")
        }
    }
} else {
    log.info("Target status is not '${TARGET_STATUS_NAME}'. No fields to lock for this status.")
}

log.info("Field locking logic executed.")

// --- Main Method -- Listen Event ---