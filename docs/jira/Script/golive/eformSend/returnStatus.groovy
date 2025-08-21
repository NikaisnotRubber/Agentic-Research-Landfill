import com.atlassian.jira.issue.Issue
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.IssueInputParameters
import com.atlassian.jira.workflow.JiraWorkflow
import com.opensymphony.workflow.loader.ActionDescriptor

// ===================================================================================
// 1. 設定區域 (請在此處修改您的配置)
// ===================================================================================

// 要轉移的 Issue Key 列表
final String[] issueKeys = [
   "PMSPG-13", "PMSPG-7"
]

// 目標轉換 (Transition) 的名稱
final def transitionId = 331

// ===================================================================================
// 2. 腳本主要邏輯 (此部分無需修改)
// ===================================================================================
// 獲取 Jira 核心服務
def issueService = ComponentAccessor.getIssueService()
def issueManager = ComponentAccessor.getIssueManager()
def workflowManager = ComponentAccessor.getWorkflowManager()
// 獲取當前登入的使用者，腳本將以此使用者的權限執行
def currentUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

if (!currentUser) {
    log.warn("無法獲取當前使用者，腳本無法執行。請確保在有使用者上下文的環境中運行。")
    return
}

log.warn "腳本開始執行，執行者: ${currentUser.name}"

issueKeys.each { issueKey ->
    Issue issue = Issues.getByKey(issueKey)
    if (!issue) {
        log.warn "找不到 Issue '${issueKey}'，跳過此 Issue。"
        return // 相當於 continue
    }

    log.warn "正在處理 Issue '${issueKey}' (目前狀態: ${issue.status.name})..."

    // 1. 從 Issue 的工作流程中找到目標 Transition
    Collection<ActionDescriptor> availableActions = issueWorkflowManager.getAvailableActions(issue)
    // 4. 在結果中尋找目標 transition
    ActionDescriptor targetAction = availableActions.find { it.id == targetTransitionId as int }
    if (!targetAction) {
        log.warn "在 Issue '${issueKey}' 的當前狀態 '${issue.status.name}' 下，找不到名為 '${transitionId}' 的可用轉換。"
        return
    }

    // 2. 準備並驗證 Transition
    IssueInputParameters issueInputParameters = issueService.newIssueInputParameters()
    // 如果轉換需要填寫欄位，可以在這裡設定，例如：
    // issueInputParameters.setComment("由腳本自動轉移")
    
    IssueService.TransitionValidationResult validationResult = issueService.validateTransition(currentUser, issue.id, targetAction.id, issueInputParameters)

    if (validationResult.isValid()) {
        // 3. 執行 Transition
        IssueService.IssueResult transitionResult = issueService.transition(currentUser, validationResult)

        if (transitionResult.isValid()) {
            log.warn "成功將 Issue '${issueKey}' 轉移到下一個狀態。"
        } else {
            log.warn "執行 Issue '${issueKey}' 的轉換失敗。"
            transitionResult.errorCollection.errors.each { key, value ->
                log.warn "  - 錯誤欄位 '${key}': ${value}"
            }
        }
    } else {
        log.warn "驗證 Issue '${issueKey}' 的轉換 '${transitionId}' 失敗。"
        validationResult.errorCollection.errorMessages.each { message ->
            log.warn "  - 錯誤訊息: ${message}"
        }
    }
}

log.warn "腳本執行完畢。"
return "腳本執行完畢。請檢查日誌以獲取詳細資訊。"