import os
import langextract as lx
from content_collector import DocumentCollector

# 全局變量
extensions = [".py", ".java", ".groovy", ".kt", ".js", ".ts", "tsx"]

file_path = "..\\docs"

prompt = """ 
# 程式碼知識提取Agent

## 任務
從程式碼中提取結構化知識，支援Java(Spring Boot)、Groovy(Jira Script Runner)、Python、Go、JS/TS、Ruby開發。

## 輸出格式

### 函數/類別
```markdown
**[語言] 名稱**: `ClassName` 或 `functionName`
**用途**: 簡潔功能描述
**參數**: param: Type - 說明
**返回值**: ReturnType - 說明  
**特殊標記**: @注解、裝飾器等
**使用範例**:
```[language]
// 實際可執行的程式碼範例
```
**注意事項**: 重要限制或配置需求
```

### 配置項目
```markdown
**配置**: `CONFIG_NAME`
**類型**: type | 預設值
**用途**: 配置作用
**設定**:
```
# 環境變數或配置文件設定方式
```
```

## 語言特定重點

- **Java/Spring**: @Component/@Service/@Controller、依賴注入、application.yml
- **Groovy/Jira**: ComponentAccessor使用、Issue操作、自定義欄位
- **Python**: Type hints、裝飾器、異常處理
- **Go**: interface、error handling、struct tags
- **JS/TS**: async/await、型別定義、模組導入
- **Ruby**: ActiveRecord關聯、gem依賴、方法定義

## 處理流程

1. **識別**: "[檢測語言] + [框架]"
2. **提取**: 按格式提取關鍵API和配置
3. **驗證**: 確保程式碼範例可執行
4. **總結**: "提取完成: X個API, Y個配置"

## 品質要求

- 函數簽名必須準確
- 程式碼範例符合語言慣例
- 標註版本差異（如Spring Boot 2/3）
- 不確定時標記 `[需驗證]`
"""

# Example for program documentation data extraction
program_doc_examples = [
    lx.data.ExampleData(
        text="""
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.CustomFieldManager

def customField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_19210")
def projectAtr = issue.getCustomFieldValue(customField)?.toString()

def mappingTable = [
    "Immersive Experience Creator": "Singyu.Liu",
    "Opportunity Lifecycle Management": "SAM.SHIH",
    "Smart e-Learning": "Hongyi.Du",
]

def corpStrategyVal = issue.getCustomFieldValue(corpStrategyId) as Map

if (mappingTable.containsKey(lastVal)) {
    programManagerVal = Users.getByName(mappingTable[lastVal])
}
""",
        extractions=[
            lx.data.Extraction(
                extraction_class="import_statement",
                extraction_text="import com.atlassian.jira.component.ComponentAccessor",
                attributes={"package": "com.atlassian.jira.component.ComponentAccessor"}
            ),
            lx.data.Extraction(
                extraction_class="jira_field",
                extraction_text="customfield_19210",
                attributes={"field_type": "custom_field", "usage": "project attribute"}
            ),
            lx.data.Extraction(
                extraction_class="function_name",
                extraction_text="getCustomFieldObject",
                attributes={"context": "accessing Jira custom field"}
            ),
            lx.data.Extraction(
                extraction_class="configuration_parameter",
                extraction_text="mappingTable",
                attributes={"type": "key-value mapping", "purpose": "program manager assignment"}
            ),
        ]
    )
]
text = """ 
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


/**
 * @Description:
 * 這是一個 Jira ScriptRunner 的自定義 REST 端點 (Custom REST Endpoint)。
 * 主要功能是接收外部系統（如 Gitlab CI/CD）的 POST 請求，根據請求內容對指定的 Jira Issue 進行審批操作。
 *
 * @Usage:
 *   - HTTP Method: POST
 *   - Endpoint URL: /rest/scriptrunner/latest/custom/postRecvTest2
 *   - Authentication: 需要以 'jira-administrators' 群組的成員身份進行認證。
 *
 * @Parameters (JSON in Request Body):
 *   - "issue_key" (String, required): 目標 Jira Issue 的 Key，例如 "PROJ-123"。
 *   - "approval" (String, required): 審批結果，必須是 "yes" 或 "no"。
 *   - "deployment_owners" (String, optional): 如果審批通過 (approval="yes")，要更新到 'Deployment Owners' 字段的值。
 *
 * @Core_Components_Used:
 *   - IssueService: 用於執行所有與 Issue 相關的核心操作，如獲取、更新和轉換 (transition) Issue。它是執行操作前進行權限和有效性驗證的首選方式。
 *   - WorkflowManager: 用於獲取 Issue 當前狀態的工作流 (workflow) 定義，並查找可用的轉換 (ActionDescriptor)。
 *   - JiraAuthenticationContext: 用於獲取當前發起請求的登入使用者資訊，以便在執行操作時進行權限檢查。
 *   - CustomFieldManager: 用於查找和管理自定義字段 (Custom Field) 的實例。
 */
// @BaseScript CustomEndpointDelegate delegate
// 這是一個 Groovy 的 AST 轉換註解，它告訴 ScriptRunner 這個腳本是一個自定義 REST 端點。
// CustomEndpointDelegate 提供了處理 HTTP 請求、生成響應等基礎功能，讓腳本可以專注於業務邏輯。
//
// postRecvTest2(...) { ... }
// 這是端點的定義和實現，它是一個閉包 (Closure)。
//   - httpMethod: "POST" -> 指定此端點僅接受 POST 方法的請求。
//   - groups: ["jira-administrators"] -> 訪問控制，只有屬於 "jira-administrators" 群組的用戶才能調用此端點。
//   - 閉包的參數 (queryParams, body, request) 由 ScriptRunner 框架在接收到請求時自動注入：
//     - queryParams: (MultivaluedMap) 存儲 URL 中的查詢參數 (e.g., ?a=1&b=2)。
//     - body: (String) HTTP 請求的主體內容，我們預期它是 JSON 格式的字符串。
//     - request: (HttpServletRequest) 原始的 Java Servlet 請求對象，可用於獲取 Header、IP 地址等更詳細的請求信息。
@BaseScript CustomEndpointDelegate delegate
postRecvTest2(httpMethod: "POST", groups: ["jira-administrators"]) { MultivaluedMap queryParams, String body, HttpServletRequest request ->
    // 初始化 - 透過 ComponentAccessor 獲取 Jira 核心組件的實例

    // IssueService: 是執行 Issue 相關操作的主要服務。
    // 它提供了創建、更新、刪除、轉換 Issue 的方法，並會自動處理權限驗證和數據有效性檢查。
    // 使用 IssueService 比直接操作 MutableIssue 物件更安全、更推薦。
    def issueService = ComponentAccessor.getIssueService()

    // CustomFieldManager: 用於獲取和管理 Jira 中的自定義字段。
    // 在這裡，我們用它來按名稱查找 'Deployment Owners' 這個字段。
    def customFieldManager = ComponentAccessor.getCustomFieldManager()

    // WorkflowManager: 用於管理和查詢 Jira 工作流。
    // 我們可以透過它獲取某個 Issue 當前所處的工作流 (JiraWorkflow) 對象，進而查詢可用的轉換(transitions)等信息。
    def workflowManager = ComponentAccessor.getWorkflowManager()

    // JiraAuthenticationContext: 提供對當前請求的安全上下文的訪問。
    // .getLoggedInUser(): 獲取當前執行此腳本的登入使用者（在這裡是 REST endpoint 的調用者）。
    // 這個使用者對象後續會被傳遞給各種 Service 方法，用於權限檢查。
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
    // 使用 issueService.getIssue() 方法來獲取 issue。
    // 這個方法需要傳入當前用戶 (loggedInUser) 和 issue key，它會檢查用戶是否有權限查看這個 issue。
    IssueService.IssueResult getIssueServiceResult = issueService.getIssue(loggedInUser, issueKey)
    def issueOutcome = handleServiceResult(getIssueServiceResult, "找不到 Issue '${issueKey}' 或無權限查看", 404)
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
            // 使用 customFieldManager 按名稱查找自定義字段。'first()' 是因為名稱可能不唯一。
            CustomField field = customFieldManager.getCustomFieldObjectsByName('Deployment Owners').first()
            if (!field) return createErrorResponse('failed', "找不到 'Deployment Owners' 自定義字段。", 500)
            
            // 為了更新 Issue，需要創建一個 IssueInputParameters 物件。
            // 這是傳遞更新數據的標準方式，它允許 Jira 進行恰當的驗證。
            def params = issueService.newIssueInputParameters()
            params.addCustomFieldValue(field.idAsLong, deploymentOwners)

            // (驗證) 在實際更新前，先使用 issueService.validateUpdate 驗證更新是否有效。
            def validation = issueService.validateUpdate(loggedInUser, issue.id, params)
            def valOutcome = handleServiceResult(validation, "驗證更新失敗", 400, true)
            if (!valOutcome.valid) {
                if (valOutcome.response) return valOutcome.response
                // 部分失敗，保留原 issue
            } else {
                // (執行) 驗證通過後，使用 issueService.update 真正執行更新。
                def updRes = issueService.update(loggedInUser, validation)
                def updOutcome = handleServiceResult(updRes, "更新字段失敗", 500, true)
                if (!updOutcome.valid && updOutcome.response) return updOutcome.response
                issue = updRes.issue // 更新成功後，獲取最新的 issue 對象
            }
        }

        // 執行 transition
        // 1. 透過 workflowManager 獲取此 issue 當前所屬的工作流定義。
        JiraWorkflow workflow = workflowManager.getWorkflow(issue)
        // 2. 獲取 issue 當前狀態 (如 'In Review') 下所有可用的轉換 (actions)。
        Collection<ActionDescriptor> actions = workflow.getLinkedStep(issue.status).actions
        // 3. 從可用轉換中，找到名稱與目標轉換名稱 (targetTransitionName，即 'Approval Got' 或 'Rejected') 匹配的轉換。
        def action = actions.find { it.name.equalsIgnoreCase(targetTransitionName) }
        if (!action) return createErrorResponse('failed', "不能執行轉換 '${targetTransitionName}'。", 400)
        
        // 4. (驗證) 在實際執行轉換前，先使用 issueService.validateTransition 驗證操作是否有效。
        //    這會檢查用戶是否有權限執行此轉換，以及是否滿足所有工作流條件 (conditions/validators)。
        //    action.id 是要執行的轉換的唯一標識符。
        def validation = issueService.validateTransition(loggedInUser, issue.id, action.id, issueService.newIssueInputParameters())
        def valTrans = handleServiceResult(validation, "驗證轉換失敗", 400)
        if (!valTrans.valid) return valTrans.response
        
        // 5. (執行) 驗證通過後，使用 issueService.transition 真正執行工作流轉換。
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
"""
documents = DocumentCollector(file_path=file_path, extensions=extensions).get_documents_for_langextract()
doc_num = documents.__sizeof__
print(f"總計找到幾個文檔 {doc_num}")

result = lx.extract(
    text_or_documents = documents,
    prompt_description=prompt,
    examples=program_doc_examples,
    # model_id="gemini-2.5-flash",  # Automatically selects OpenAI provider
    # api_key=os.environ.get('GEMINI_API_KEY'),

    model_id=os.environ.get('OPENAI_MODEL', 'delta-agentic-coding'),  # Automatically selects OpenAI provider
    api_key=os.environ.get('OPENAI_API_KEY'),
    model_url=os.environ.get('OPENAI_BASE_URL'),
    max_workers=16,
    fence_output=False,
    use_schema_constraints=True
)

