import com.atlassian.jira.util.ErrorCollection
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.bc.ServiceResultImpl
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.workflow.JiraWorkflow

import com.onresolve.scriptrunner.runner.rest.common.CustomEndpointDelegate
import com.opensymphony.workflow.loader.ActionDescriptor

import groovy.transform.BaseScript
import groovy.json.JsonSlurper
import javax.ws.rs.core.MultivaluedMap
import javax.ws.rs.core.Response
import javax.servlet.http.HttpServletRequest

// 初始化回傳結果
def result = [
    timestamp: new Date().toString(),
    status: "unknown",
    message: "",
    issue_key: null,
    action_taken: null
]

// 通用錯誤處理工具方法
def createErrorResponse = { String status, String message, int httpStatus ->
    result.status = status
    result.message = message
    log.warn(message)
    return Response.status(httpStatus).entity(result).build()
}

def handleServiceResult = { ServiceResultImpl serviceResult, String context, int httpStatus = 500, boolean isPartial = false ->
    if (serviceResult?.respondsTo('isValid') && !serviceResult.isValid()) {
        def ec = serviceResult.respondsTo('getErrorCollection') ? serviceResult.errorCollection : null
        // inline ErrorCollection to message
        def errorMsg = 'Unknown error.'
        if (ec) {
            List<String> fieldErrors = ec.errors?.collect { k, v -> "${k}: ${v}".toString() } ?: []
            List<String> generalErrors = ec.errorMessages?.collect { it.toString() } ?: []
            errorMsg = (fieldErrors + generalErrors).join('; ') ?: 'Unknown error.'
        }
        if (isPartial) {
            result.status = 'partial_failure'
            result.message = "${context}，但將繼續。錯誤: ${errorMsg}"
            log.warn("${context}: ${errorMsg}")
            return [valid: false, partial: true]
        }
        return [valid: false, response: createErrorResponse('failed', "${context}: ${errorMsg}", httpStatus)]
    }
    [valid: true]
}


@BaseScript CustomEndpointDelegate delegate
eformPoseRecv(httpMethod: "POST", groups: ["jira-administrators"]) { MultivaluedMap queryParams, String body, HttpServletRequest request ->
    // 初始化
    def issueService = ComponentAccessor.getIssueService()
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def workflowManager = ComponentAccessor.getWorkflowManager()
    def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

    // 1. 驗證和解析請求體
    if (!body || body.trim().isEmpty()) { return createErrorResponse("failed", "請求體不能為空 (Request body cannot be empty)", 400) }
    Map parsedBody; try { parsedBody = new JsonSlurper().parseText(body) as Map } catch (Exception e) { return createErrorResponse("failed", "解析請求 Body 時發生錯誤 (Invalid JSON format): ${e.getMessage()}", 400) }

    // 2. 獲取請求參數
    String itsysdng_key = parsedBody?.issue_key; if (!itsysdng_key) { return createErrorResponse("failed", "請求體中必須包含 'itsysdng_key'", 400) }
    String approvalStatus = parsedBody?.approval; String deploymentOwners = parsedBody?.deployment_owners; result.issue_key = itsysdng_key
    if (!approvalStatus || !(approvalStatus in ["yes", "no"])) { return createErrorResponse("failed", "approval 屬性缺失或值無效 (必須是 'yes' 或 'no')", 400) }
    log.warn("處理參數: issue_key='${itsysdng_key}', approval='${approvalStatus}', deployment_owners='${deploymentOwners}'")

    // 3. 獲取 Issue 物件
    IssueService.IssueResult getIssueServiceResult = issueService.getIssue(loggedInUser, itsysdng_key)
    def issueOutcome = handleServiceResult(getIssueServiceResult, "找不到 Issue '${itsysdng_key}' 或無權限查看", 404)
    if (!issueOutcome.valid) return issueOutcome.response // 如果獲取失敗，直接返回錯誤
    MutableIssue issue = getIssueServiceResult.getIssue()

    // 4. 根據 approval 狀態執行操作
    try {
        def transitionMap = [yes: 'Approval Got', no: 'Rejected']
        String targetTransitionName = transitionMap[approvalStatus]
        result.action_taken = approvalStatus == 'yes' ?
            "Updating 'Deployment Owners' and transitioning to '${targetTransitionName}'." :
            "Transitioning to '${targetTransitionName}'."

        // 如果提供 deploymentOwners，嘗試更新自定義字段
        if (deploymentOwners) {
            CustomField field = customFieldManager.getCustomFieldObjectsByName('Deployment Owners').first()
            if (!field) return createErrorResponse('failed', "找不到 'Deployment Owners' 自定義字段。", 500)
            def params = issueService.newIssueInputParameters()
            params.addCustomFieldValue(field.idAsLong, deploymentOwners)
            def validation = issueService.validateUpdate(loggedInUser, issue.id, params)
            def valOutcome = handleServiceResult(validation, "驗證更新失敗", 400, true)
            if (!valOutcome.valid) {
                if (valOutcome.response) return valOutcome.response
                // 部分失敗，保留原 issue
            } else {
                def updRes = issueService.update(loggedInUser, validation)
                def updOutcome = handleServiceResult(updRes, "更新字段失敗", 500, true)
                if (!updOutcome.valid && updOutcome.response) return updOutcome.response
                issue = updRes.issue
            }
        }

        // 執行 transition
        JiraWorkflow workflow = workflowManager.getWorkflow(issue)
        Collection<ActionDescriptor> actions = workflow.getLinkedStep(issue.status).actions
        def action = actions.find { it.name.equalsIgnoreCase(targetTransitionName) }
        if (!action) return createErrorResponse('failed', "不能執行轉換 '${targetTransitionName}'。", 400)
        def validation = issueService.validateTransition(loggedInUser, issue.id, action.id, issueService.newIssueInputParameters())
        def valTrans = handleServiceResult(validation, "驗證轉換失敗", 400)
        if (!valTrans.valid) return valTrans.response
        def transRes = issueService.transition(loggedInUser, validation)
        def transOutcome = handleServiceResult(transRes, "執行轉換失敗", 500)
        if (!transOutcome.valid) return transOutcome.response

        result.status = 'success'
        result.message = "Issue '${issue.key}' 已成功轉換到 ${targetTransitionName}."
    } catch (Exception e) {
        log.error("內部錯誤: ${e.message}", e)
        return createErrorResponse('error', "內部錯誤: ${e.message}", 500)
    }

    // 返回結果
    return Response.status(result.status == 'success' ? 200 : 400).entity(result).build()
}