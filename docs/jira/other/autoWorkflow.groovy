import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.workflow.ConfigurableJiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.workflow.WorkflowUtil
import com.atlassian.jira.workflow.WorkflowSchemeManager
import com.atlassian.jira.workflow.WorkflowScheme
import com.atlassian.jira.issue.issuetype.IssueType
import com.opensymphony.workflow.loader.WorkflowDescriptor
import com.opensymphony.workflow.loader.StepDescriptor
import com.opensymphony.workflow.loader.ActionDescriptor
import com.opensymphony.workflow.loader.ResultDescriptor
import org.apache.log4j.Logger
import org.apache.log4j.Level

// =====================================================================================
// CONFIGURATION - 請在此處修改您的變量
// =====================================================================================
final String WORKFLOW_NAME = "Mermaid Process Workflow" // 自定義您的工作流程名稱
final String ISSUE_TYPE_NAME = "Task" // 指定要關聯的 Issue Type，例如 "Task", "Bug"

// =====================================================================================
// SCRIPT LOGIC - 以下為腳本主體
// =====================================================================================

def log = Logger.getLogger("AutoWorkflowGenerator")
log.setLevel(Level.DEBUG)

def workflowManager = ComponentAccessor.getWorkflowManager()
def constantsManager = ComponentAccessor.getConstantsManager()
def issueTypeManager = ComponentAccessor.getIssueTypeManager()

// --- 1. 檢查並刪除已存在的工作流程 ---
def existingWorkflow = workflowManager.getWorkflow(WORKFLOW_NAME)
if (existingWorkflow) {
    log.warn("工作流程 '${WORKFLOW_NAME}' 已存在。正在刪除舊版本...")
    try {
        workflowManager.deleteWorkflow(existingWorkflow)
        log.info("舊的工作流程 '${WORKFLOW_NAME}' 已成功刪除。")
    } catch (Exception e) {
        log.error("刪除工作流程 '${WORKFLOW_NAME}' 失敗: ${e.message}", e)
        return "刪除失敗，腳本終止。"
    }
}

// --- 2. 創建新的工作流程 ---
ConfigurableJiraWorkflow newWorkflow = new ConfigurableJiraWorkflow(WORKFLOW_NAME, workflowManager)
WorkflowDescriptor descriptor = newWorkflow.getDescriptor()
log.info("正在創建新的工作流程: '${WORKFLOW_NAME}'")

// --- 3. 定義狀態 (Statuses) ---
// This helper function will find a status by name or throw an error if not found.
// Ensure these statuses exist in your Jira instance before running the script.
def statusTodo = findStatus("待辦")
def statusInProgress = findStatus("進行中")
def statusInReview = findStatus("審核中")
def statusDone = findStatus("完成")
def statusNeedsRevision = findStatus("需要修改")
def statusReopened = findStatus("重新開啟")
def statusCanceled = findStatus("已取消")
def statusClosed = findStatus("已封鎖")


// --- 4. 在工作流程中創建步驟 (Steps) ---
def stepTodo = newWorkflow.addStep(1, statusTodo.getName())
stepTodo.getMetaAttributes().put("jira.status.id", statusTodo.getId())

def stepInProgress = newWorkflow.addStep(2, statusInProgress.getName())
stepInProgress.getMetaAttributes().put("jira.status.id", statusInProgress.getId())

def stepInReview = newWorkflow.addStep(3, statusInReview.getName())
stepInReview.getMetaAttributes().put("jira.status.id", statusInReview.getId())

def stepDone = newWorkflow.addStep(4, statusDone.getName())
stepDone.getMetaAttributes().put("jira.status.id", statusDone.getId())

def stepNeedsRevision = newWorkflow.addStep(5, statusNeedsRevision.getName())
stepNeedsRevision.getMetaAttributes().put("jira.status.id", statusNeedsRevision.getId())

def stepReopened = newWorkflow.addStep(6, statusReopened.getName())
stepReopened.getMetaAttributes().put("jira.status.id", statusReopened.getId())

def stepCanceled = newWorkflow.addStep(7, statusCanceled.getName())
stepCanceled.getMetaAttributes().put("jira.status.id", statusCanceled.getId())

def stepClosed = newWorkflow.addStep(8, statusClosed.getName())
stepClosed.getMetaAttributes().put("jira.status.id", statusClosed.getId())

// --- 5. 創建轉換 (Transitions) ---
// Action IDs must be unique within the workflow
createTransition(newWorkflow, stepTodo, stepInProgress, 11, "開始處理")
createTransition(newWorkflow, stepInProgress, stepInReview, 21, "提交審核")
createTransition(newWorkflow, stepInProgress, stepClosed, 31, "問題解決")
createTransition(newWorkflow, stepInReview, stepDone, 41, "通過")
createTransition(newWorkflow, stepInReview, stepNeedsRevision, 51, "退回修改")
createTransition(newWorkflow, stepNeedsRevision, stepInProgress, 61, "再次處理")
createTransition(newWorkflow, stepDone, stepReopened, 71, "發現問題")
createTransition(newWorkflow, stepReopened, stepInProgress, 81, "重新處理")

// Create global transitions to "Canceled"
createGlobalTransition(descriptor, stepCanceled, 91, "取消任務")


// --- 6. 設置初始步驟 ---
// The first transition created from the initial step becomes the initial action
newWorkflow.setInitialStep(stepTodo)

// --- 7. 保存工作流程 ---
try {
    workflowManager.createWorkflow(newWorkflow)
    log.info("工作流程 '${WORKFLOW_NAME}' 已成功創建。")
} catch (Exception e) {
    log.error("創建工作流程 '${WORKFLOW_NAME}' 失敗: ${e.message}", e)
    return "創建失敗，腳本終止。"
}

// --- 8. 將工作流程與 Issue Type 關聯 (可選) ---
log.warn("關聯工作流程到 Issue Type 的部分建議您在 Jira 管理後台的 'Workflow Schemes' 手動完成，以確保正確性。")
def issueType = issueTypeManager.getIssueTypes().find { it.getName() == ISSUE_TYPE_NAME }
if (!issueType) {
    log.warn("找不到指定的 Issue Type: '${ISSUE_TYPE_NAME}'。請手動關聯工作流程。")
}


return "腳本執行完畢。工作流程 '${WORKFLOW_NAME}' 已創建。"


// --- Helper Functions ---

/**
 * Finds a status by its name (case-insensitive).
 * @param name The name of the status to find.
 * @return The Status object.
 * @throws IllegalStateException if the status is not found.
 */
def findStatus(String name) {
    def status = ComponentAccessor.getConstantsManager().getAllStatusObjects().find { it.getName().equalsIgnoreCase(name) }
    if (status) {
        return status
    }
    throw new IllegalStateException("找不到狀態: '${name}'。請先在 Jira > Issues > Statuses 中創建此狀態。")
}

/**
 * Creates a transition between two steps.
 * @param workflow The workflow to add the transition to.
 * @param fromStep The starting step.
 * @param toStep The destination step.
 * @param actionId A unique ID for the action.
 * @param actionName The name of the transition.
 */
def createTransition(ConfigurableJiraWorkflow workflow, StepDescriptor fromStep, StepDescriptor toStep, int actionId, String actionName) {
    // Create the action descriptor
    ActionDescriptor action = workflow.getDescriptor().getAction(actionId)
    if (action == null) {
        action = com.opensymphony.workflow.loader.WorkflowDescriptor.createAction(actionId)
    }
    action.setName(actionName)
    action.getMetaAttributes().put("jira.i18n.title", actionName)
    
    // Set the result of the action to move to the destination step
    ResultDescriptor result = new ResultDescriptor()
    result.setOldId(actionId)
    result.setStep(toStep.getId())
    result.setStatus(toStep.getName())
    action.setUnconditionalResult(result)

    // Add the action to the starting step
    fromStep.addAvailableAction(action)
    workflow.getDescriptor().addAction(action)
}

/**
 * Creates a global transition that can be triggered from any status to a specific destination step.
 * @param descriptor The workflow descriptor.
 * @param toStep The destination step for the global transition.
 * @param actionId A unique ID for the action.
 * @param actionName The name of the transition.
 */
def createGlobalTransition(WorkflowDescriptor descriptor, StepDescriptor toStep, int actionId, String actionName) {
    ActionDescriptor action = descriptor.getGlobalAction(actionId)
    if (action == null) {
        action = com.opensymphony.workflow.loader.WorkflowDescriptor.createAction(actionId)
    }
    action.setName(actionName)
    action.getMetaAttributes().put("jira.i18n.title", actionName)

    ResultDescriptor result = new ResultDescriptor()
    result.setOldId(actionId)
    result.setStep(toStep.getId())
    result.setStatus(toStep.getName())
    action.setUnconditionalResult(result)

    descriptor.addGlobalAction(action)
}