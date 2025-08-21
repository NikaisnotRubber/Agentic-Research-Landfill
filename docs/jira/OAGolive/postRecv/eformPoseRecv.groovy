import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.bc.ServiceResultImpl
import com.atlassian.jira.bc.issue.IssueService.TransitionValidationResult
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.user.ApplicationUser

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import com.opensymphony.workflow.loader.ActionDescriptor

import groovy.transform.BaseScript
import groovy.json.JsonSlurper
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import javax.servlet.http.HttpServletRequest




// 通用錯誤處理工具方法
def createErrorResponse = { String status, String message, int httpStatus ->
    result.status = status
    result.message = message
    log.warn(message)
    return Response.status(httpStatus).entity(result).build()
}

// 處理 ServiceResult 的通用方法
def handleServiceResult = { ServiceResultImpl serviceResult, String context, int httpStatus = 500, boolean isPartialFailure = false ->
    if (serviceResult?.metaClass?.respondsTo(serviceResult, 'isValid') && !serviceResult.isValid()) {
        String errorMsg = "Unknown error."
        try {
            if (serviceResult?.metaClass?.respondsTo(serviceResult, 'getErrorCollection')) {
                def errorCollection = serviceResult.getErrorCollection()
                if (errorCollection) {
                    def fieldErrors = errorCollection.getErrors()
                    def fieldErrorMsg = fieldErrors ? fieldErrors.collect { key, value -> "${key}: ${value}" }.join("; ") : ""
                    
                    def generalErrors = errorCollection.getErrorMessages()
                    def generalErrorMsg = generalErrors ? generalErrors.join(", ") : ""
                    
                    def combinedErrors = [fieldErrorMsg, generalErrorMsg].findAll { it }.join("; ")
                    errorMsg = combinedErrors ?: "Unknown error."
                } else {
                    errorMsg = "No error collection available."
                }
            }
        } catch (Exception e) {
            log.warn("獲取錯誤信息時發生例外: ${e.getMessage()}")
            errorMsg = "無法獲取詳細錯誤信息: ${e.getMessage()}"
        }
        
        if (isPartialFailure) {
            result.status = "partial_failure"
            result.message = "${context}，但將繼續嘗試轉換。錯誤: ${errorMsg}"
            log.warn("${context}: ${errorMsg}")
            // 回傳一個明確表示「部分失敗」的物件
            return [valid: false, partial: true]
        } else {
            def errorResponse = createErrorResponse("failed", "${context}: ${errorMsg}", httpStatus)
            // 回傳一個明確表示「完全失敗」並包含 Response 的物件
            return [valid: false, partial: false, response: errorResponse]
        }
    }
    // 回傳一個明確表示「成功」的物件
    return [valid: true]
}


@BaseScript CustomEndpointDelegate delegate
recvEform(httpMethod: "POST", groups: ["jira-administrators"]) { MultivaluedMap queryParams, String body, HttpServletRequest request ->
    def issueService = ComponentAccessor.getIssueService()
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def workflowManager = ComponentAccessor.getWorkflowManager()
    def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

    // 初始化回傳結果
    def result = [
        InstanceCode: "",
        status: "",
        message: "",
    ]

    if (!body || body.trim().isEmpty()) { return createErrorResponse("failed", "請求體不能為空 (Request body cannot be empty)", 400) }
    Map parsedBody; try { parsedBody = new JsonSlurper().parseText(body) as Map } catch (Exception e) { return createErrorResponse("failed", "解析請求 Body 時發生錯誤 (Invalid JSON format): ${e.getMessage()}", 400) }
}