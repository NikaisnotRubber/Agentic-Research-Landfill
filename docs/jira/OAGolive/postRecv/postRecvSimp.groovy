import com.atlassian.jira.util.ErrorCollection
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

// 扁平化處理 ServiceResult 的函數式實現
def handleServiceResult = { ServiceResultImpl serviceResult, String context, int httpStatus = 500, boolean isPartial = false ->
    if (serviceResult?.respondsTo('isValid') && !serviceResult.isValid()) {
        def ec = serviceResult.respondsTo('getErrorCollection') ? serviceResult.errorCollection : null
        def errorMsg = ec ? extractErrors(ec) : 'Unknown error.'
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
postRecvTest2(httpMethod: "POST", groups: ["jira-administrators"]) { MultivaluedMap queryParams, String body, HttpServletRequest request ->
    // 初始化
    def issueService = ComponentAccessor.getIssueService()
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def workflowManager = ComponentAccessor.getWorkflowManager()
    def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

    // 1. 驗證和解析請求體
    if (!body || body.trim().isEmpty()) { return createErrorResponse("failed", "請求體不能為空 (Request body cannot be empty)", 400) }
    Map parsedBody; try { parsedBody = new JsonSlurper().parseText(body) as Map } catch (Exception e) { return createErrorResponse("failed", "解析請求 Body 時發生錯誤 (Invalid JSON format): ${e.getMessage()}", 400) }

    // 2. 獲取請求參數
    String issueKey = parsedBody?.issue_key; if (!issueKey) { return createErrorResponse("failed", "請求體中必須包含 'issue_key'", 400) }
    String approvalStatus = parsedBody?.approval; String deploymentOwners = parsedBody?.deployment_owners; result.issue_key = issueKey
    if (!approvalStatus || !(approvalStatus in ["yes", "no"])) { return createErrorResponse("failed", "approval 屬性缺失或值無效 (必須是 'yes' 或 'no')", 400) }
    log.warn("處理參數: issue_key='${issueKey}', approval='${approvalStatus}', deployment_owners='${deploymentOwners}'")

    // 3. 獲取 Issue 物件
    IssueService.IssueResult getIssueServiceResult = issueService.getIssue(loggedInUser, issueKey)
    def issueOutcome = handleServiceResult(getIssueServiceResult, "找不到 Issue '${issueKey}' 或無權限查看", 404)
    if (!issueOutcome.valid) return issueOutcome.response // 如果獲取失敗，直接返回錯誤
    MutableIssue issue = getIssueServiceResult.getIssue()

    // 轉換執行方法 (修改判斷方式)
    def executeTransition = { ApplicationUser user, MutableIssue currentIssue, String targetTransitionName ->
        log.warn("進入 executeTransition 方法: 準備為 Issue '${currentIssue.key}' 執行轉換 '${targetTransitionName}'，執行身份為 '${user.name}'")
        JiraWorkflow workflow = workflowManager.getWorkflow(currentIssue)
        Collection<ActionDescriptor> availableActions = workflow.getLinkedStep(issue.getStatus()).getActions()
        
        ActionDescriptor targetAction = availableActions.find { action ->
            action.getName().equalsIgnoreCase(targetTransitionName)
        }
        
        if (!targetAction) { return createErrorResponse("failed", "轉換 '${targetTransitionName}' 對於 Issue '${currentIssue.key}' 在其當前狀態下不可用。", 400) }
        int actionId = targetAction.getId()
        log.warn("找到目標轉換: '${targetAction.getName()}' (ID: ${actionId})")
        IssueInputParameters issueInputParameters = issueService.newIssueInputParameters()
        
        TransitionValidationResult validationResult = issueService.validateTransition(user, currentIssue.getId(), actionId, issueInputParameters)
        def validationOutcome = handleServiceResult(validationResult, "驗證轉換 '${targetTransitionName}' 失敗", 400)
        if (!validationOutcome.valid) return validationOutcome.response // 如果驗證失敗，直接返回錯誤

        log.warn("轉換 '${targetTransitionName}' 驗證成功，準備執行。")
        IssueService.IssueResult transitionResult = issueService.transition(user, validationResult)
        def transitionOutcome = handleServiceResult(transitionResult, "轉換 Issue '${currentIssue.key}' 失敗", 500)
        if (!transitionOutcome.valid) return transitionOutcome.response // 如果執行失敗，直接返回錯誤

        log.warn("Issue '${currentIssue.key}' 成功轉換到 '${targetTransitionName}'.")
        result.status = "success"
        result.message = "Issue '${currentIssue.key}' 已成功處理: ${targetTransitionName}."
        return null
    }

    // 批量部署擁有者欄位更新的函數式處理
    def updateDeploymentOwners = { ApplicationUser user, MutableIssue currentIssue, String owners ->
        CustomField field = customFieldManager.getCustomFieldObjectsByName('Deployment Owners').first()
        if (!field) {
            return createErrorResponse('failed', "配置錯誤：找不到 'Deployment Owners' 自定義字段。", 500)
        }
        def params = issueService.newIssueInputParameters()
        params.addCustomFieldValue(field.getIdAsLong(), owners)
        def validation = issueService.validateUpdate(user, currentIssue.getId(), params)
        def valOutcome = handleServiceResult(validation, "驗證更新 Issue '${currentIssue.key}' 字段失敗", 400, true)
        if (!valOutcome.valid) {
            if (valOutcome.response) return valOutcome.response
            // 部分失敗時，跳過更新，返回原 Issue
            return currentIssue
        }
        def updRes = issueService.update(user, validation)
        def updOutcome = handleServiceResult(updRes, "更新 Issue '${currentIssue.key}' 字段失敗", 500, true)
        if (!updOutcome.valid) {
            if (updOutcome.response) return updOutcome.response
            // 部分失败时，返回原 Issue（不应用字段更新）
            return currentIssue
        }
        return updRes.getIssue()
    }

    // 4. 根據 approval 狀態執行操作
    try {
        // 根據 approvalStatus 決定轉換名稱
        def transitionMap = [yes: 'Approval Got', no: 'Rejected']
        String targetTransitionName = transitionMap[approvalStatus]
        result.action_taken = approvalStatus == 'yes' ?
            "Attempting to update 'Deployment Owners' and transition to '${targetTransitionName}'." :
            "Attempting transition '${targetTransitionName}'."

        // 准備 Issue：函數式地更新 Deployment Owners（如果提供），並確保類型正確
        def modified = deploymentOwners ? updateDeploymentOwners(loggedInUser, issue, deploymentOwners) : issue
        if (modified instanceof Response) {
            return modified
        }
        if (!(modified instanceof MutableIssue)) {
            return createErrorResponse('failed', "更新後收到非 Issue 類型: ${modified?.getClass()}", 500)
        }
        issue = (MutableIssue) modified

        def transitionResponse = executeTransition(loggedInUser, issue, targetTransitionName)
        if (transitionResponse) return transitionResponse
        
    } catch (Exception e) {
        log.error("處理請求時發生未預期的內部錯誤: ${e.message}", e)
        return createErrorResponse("error", "處理請求時發生內部錯誤: ${e.getMessage()}", 500) 
    }

    log.warn("最終處理結果: ${result}")
    return Response.status(result.status == "success" ? 200 : 400).entity(result).build()
}