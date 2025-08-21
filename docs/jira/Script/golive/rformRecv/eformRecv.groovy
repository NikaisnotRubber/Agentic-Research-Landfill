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
    code: "",
    message: "",
]

// 通用錯誤處理工具方法
def createErrorResponse = { String status, String message, int httpStatus ->
    result.code = httpStatus.toString()
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
            result.code = httpStatus.toString()
            result.message = "${context}，但將繼續。錯誤: ${errorMsg}"
            log.warn("${context}: ${errorMsg}")
            return [valid: false, partial: true]
        }
        return [valid: false, response: createErrorResponse('failed', "${context}: ${errorMsg}", httpStatus)]
    }
    [valid: true]
}


@BaseScript CustomEndpointDelegate delegate
oA2JiraRecvTest(httpMethod: "POST", groups: ["jira-administrators"]) { MultivaluedMap queryParams, String body, HttpServletRequest request ->
    // 初始化
    def issueService = ComponentAccessor.getIssueService()
    def customFieldManager = ComponentAccessor.getCustomFieldManager()
    def workflowManager = ComponentAccessor.getWorkflowManager()
    def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

    // 1. 驗證和解析請求體
    if (!body || body.trim().isEmpty()) { return createErrorResponse("failed", "請求體不能為空 (Request body cannot be empty)", 400) }
    Map parsedBody; try { parsedBody = new JsonSlurper().parseText(body) as Map } catch (Exception e) { return createErrorResponse("failed", "解析請求 Body 時發生錯誤 (Invalid JSON format): ${e.getMessage()}", 400) }

    // 2. 獲取請求參數
    String issueKey = parsedBody?."itsysdng_key"; if (!issueKey) { return createErrorResponse("failed", "請求體中必須包含 'issue_key'", 400) }
    String approvalStatus = parsedBody?."Approval_result";
    if (!approvalStatus || !(approvalStatus in ["Approval", "Reject"])) { return createErrorResponse("failed", "approval 屬性缺失或值無效 (必須是 'Approval' 或 'Reject')", 400) }
    String rejReason = parsedBody?."Reject_reason";
    log.warn("處理參數: issue_key='${issueKey}', approval='${approvalStatus}', reject_reason='${rejReason}'")

    // 3. 獲取 Issue 物件
    IssueService.IssueResult getIssueServiceResult = issueService.getIssue(loggedInUser, issueKey)
    def issueOutcome = handleServiceResult(getIssueServiceResult, "找不到 Issue '${issueKey}' 或無權限查看", 404)
    if (!issueOutcome.valid) return issueOutcome.response
    MutableIssue issue = getIssueServiceResult.getIssue()

    // 4. 根據 approval 狀態執行操作
    try {
        // --- 步驟 A: 執行工作流程轉換 ---
        def transitionMap = [Approval: 'Approval Got', Reject: 'Rejected']
        String targetTransitionName = transitionMap[approvalStatus]
        
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

        // 【重要】轉換成功後，我們需要重新獲取最新的 issue 物件，因為它的狀態和欄位可能已經改變
        Issue updatedIssue = Issues.getByKey(issue.key)//issueService.getIssue(loggedInUser, issue.key).getIssue()
        
        // --- 步驟 B: 如果是拒絕且有原因，則使用 IssueService 更新自訂欄位 ---
        def rejectUpdateMessage = "" // 用於附加到最終成功訊息中
        if (approvalStatus == 'Reject' && rejReason) {
            log.warn("檢測到拒絕原因，準備更新 'Reject Reason' 欄位...")
            CustomField rejectReasonField = customFieldManager.getCustomFieldObjectsByName('Reject Reason').first()
            
            if (rejectReasonField) {
                // 使用 IssueService 的標準流程來更新欄位
                def issueInputParameters = issueService.newIssueInputParameters()
                issueInputParameters.addCustomFieldValue(rejectReasonField.idAsLong, rejReason)
                
                def updateValidation = issueService.validateUpdate(loggedInUser, updatedIssue.id, issueInputParameters)
                // 使用 handleServiceResult 處理驗證失敗，isPartial=true 表示即使這一步失敗，也只記錄警告，不中斷整個流程
                def updateValOutcome = handleServiceResult(updateValidation, "更新拒絕原因時驗證失敗", 500, true)

                if (updateValOutcome.valid) {
                    def updateResult = issueService.update(loggedInUser, updateValidation)
                    def updateOutcome = handleServiceResult(updateResult, "更新拒絕原因時執行失敗", 500, true)
                    if (updateOutcome.valid) {
                        rejectUpdateMessage = " 拒絕原因已成功更新。"
                    } else {
                        // 如果更新執行失敗，將錯誤訊息附加到最終結果中
                        rejectUpdateMessage = updateOutcome.message 
                    }
                } else {
                    rejectUpdateMessage = updateValOutcome.message
                }
            } else {
                rejectUpdateMessage = " 但找不到 'Reject Reason' 欄位，無法更新原因。"
                log.warn(rejectUpdateMessage)
            }
        }

        // --- 步驟 C: 所有操作成功後，建立並回傳最終的成功響應 ---
        result.code = '200'
        result.message = "Issue '${issue.key}' 已成功轉換到 '${targetTransitionName}'." + rejectUpdateMessage
        return Response.status(200).entity(result).build()

    } catch (Exception e) {
        log.warn("內部錯誤: ${e.message}", e)
        return createErrorResponse('error', "內部錯誤: ${e.message}", 500)
    }
}