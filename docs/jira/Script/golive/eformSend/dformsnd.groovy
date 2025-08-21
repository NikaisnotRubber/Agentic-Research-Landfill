import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.issue.history.ChangeItemBean
// import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.bc.issue.IssueService
import com.atlassian.jira.event.type.EventDispatchOption

import groovy.json.JsonBuilder 
import groovy.json.JsonOutput
import groovyx.net.http.HTTPBuilder
import static groovyx.net.http.Method.POST
import static groovyx.net.http.ContentType.JSON

import java.time.ZoneId
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

// ===================================================================================
// 1. 設定區域 (請在此處修改您的配置)
// ===================================================================================
// Jira 相關設定
def projectKey = "PMSPG"
def targetStatus = "Go-Live Approval"
def projectReviewerCfName = "Project Reviewer" // <--- 請確認這個 "專案審核者" 欄位名稱是否正確

// 遠端 URL 及驗證資訊
// def targetUrl = "https://jirastage.deltaww.com/rest/scriptrunner/latest/custom/JiraPostRecv" // <--- 請務必替換成您要發送的真實 URL

def targetUrl = "https://dgdvap.deltaww.com/OACWebApi/api/Route/PMS_CreateGolive" // << 替換成您要發送的目標 URL
def username = " GoLiveApproval" // << 如果需要認證，請填寫用戶名
def password = "DEV5W4Ss33_53Xq" // << 如果需要認證，請填寫密碼或 API Token

// 獲取申請者的 User 物件和數字 ID
def jobRunnerUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
Long applyerNumericId = /* jobRunnerUser.getId()  */31508850
log.warn("腳本執行者 (Applyer) 為 '${jobRunnerUser.getName()}'，其數字 ID 為: ${applyerNumericId}")

// ===================================================================================
// 2. 輔助函數
// ===================================================================================
def customFieldManager = ComponentAccessor.getCustomFieldManager()

def getSafeCfValue(Issue issue, String cfName) {
    def cfList = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectsByName(cfName)
    if (cfList.isEmpty()) {
        log.warn("警告：在議題 ${issue.key} 上找不到名為 '${cfName}' 的自訂欄位。 ")
        return null
    }
    def cf = cfList.first()
    def val = null
    try {
        val = issue.getCustomFieldValue(cf)
    } catch (Exception e) {
        return null
    }
    // 處理特殊資料類型
    if (val instanceof java.sql.Timestamp) {
        val = val.toLocalDateTime()
    }

    return val
}

String getJsonSafeString(Issue issue, String cfName) {
    def rawValue = getSafeCfValue(issue, cfName)
    
    if (rawValue == null) return null
    
    if (rawValue instanceof java.util.Collection) {
        return rawValue.collect { it?.toString() }.join(', ')
    }
    
    /* if (rawValue instanceof java.util.Map) {
        def stringEntries = rawValue.collect { key, value ->
            def safeKey = key?.toString() ?: "null_key" 
            def safeValue = value?.toString() ?: "null_value"
            return "'${safeKey}':'${safeValue}'"
        }
        return "{${stringEntries.join(', ')}}"
    } */
    
    return rawValue.toString()
}

def getSafeValFromMap(Issue issue, String cfName, Integer index) {
    def rawValue = getSafeCfValue(issue, cfName)
    
    if (!(rawValue instanceof java.util.Map)) return null
    
    def vals = rawValue.collect { key, value ->
        return value?.toString()
    }

    try {
        return vals.get(index)
    } catch (Exception e){
        return null
    }
}

def getSafePlatAndModVal(Issue issue, String cfName, Integer index) {
    def isMicroForm = getSafeCfValue(issue, "Is Micro-E-form(SFS Form)")
    if(isMicroForm == "No") return getSafeValFromMap(issue, cfName, index)
    //  變更為微表單的Platform and Module
    cfName = "Correspond Micro-Form"
    return getSafeValFromMap(issue, cfName, index)
}

def getSafeUserID(Issue issue, String cfName) {
    def userObject = getSafeCfValue(issue, cfName) 

    // 多人
    if (userObject instanceof java.util.Collection) {
        return userObject.collect { user -> user?.getId() }.join(', ')
    }

    userObject = userObject as ApplicationUser
    if (userObject) {
        Long userId = userObject.getId()
        String displayName = userObject.getDisplayName()

        return userId
    } else {
        return null
    }
}

// 2. 取得指定狀態的最近切換時間
Date getLastTransitionDateToStatus(Issue issue, String targetStatusName) {
    def changeHistoryManager = ComponentAccessor.getChangeHistoryManager()
    List<ChangeItemBean> statusChanges = changeHistoryManager.getChangeItemsForField(issue, "status")
    
    if (!statusChanges) {
        if (issue.status.name == targetStatusName) return issue.created
        return null
    }

    Collections.reverse(statusChanges)
    
    def targetChange = statusChanges.find { it.getToString() == targetStatusName }
    log.warn(targetChange)
    
    if (targetChange) {
        return targetChange.getCreated()
    } else {
        // 再次檢查創建時的狀態
        if (issue.status.name == targetStatusName) return issue.created
    }
    
    return null
}

// ===================================================================================
// 3. 計算撈取時間範圍 & JQL 查詢
// ===================================================================================
def taipeiZone = ZoneId.of("Asia/Taipei")
def now = LocalDateTime.now(taipeiZone)

def lastWednesday = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.WEDNESDAY))
if (now.dayOfWeek == DayOfWeek.WEDNESDAY && now.hour < 22) {
    lastWednesday = lastWednesday.minusWeeks(1)
}
def startTime = lastWednesday.withHour(10).withMinute(0).withSecond(0)

def dateOnlyFormatter = DateTimeFormatter.ofPattern("yyyy/M/d")
def dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")

def currentTimeFormatted = now.format(dateTimeFormatter)
def scheduleJobTriggerDate = "${startTime.format(dateOnlyFormatter)}~${now.format(dateOnlyFormatter)}"

def jqlQuery = "project = '${projectKey}' AND status = '${targetStatus}' ORDER BY created DESC"
log.warn("Executing JQL: ${jqlQuery}")

def searchService = ComponentAccessor.getComponent(com.atlassian.jira.bc.issue.search.SearchService)
def user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
def loggedInUser = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()

def jqlParser = ComponentAccessor.getComponent(JqlQueryParser)
def query = jqlParser.parseQuery(jqlQuery)
def searchResults = searchService.search(user, query, PagerFilter.getUnlimitedFilter())
def issues = searchResults.getResults()
log.warn("找到 ${issues.size()} 個符合條件的議題。 ")
if (issues.isEmpty()) {
    log.warn("沒有需要處理的議題，腳本執行結束。 ")
    return
}

// 根據變換為 "Prepare Go-Live Doc" 的時間進行過濾
issues = issues.findAll {
    Date transitionDate = getLastTransitionDateToStatus(it, targetStatus)
    if (transitionDate) {
        // 將 Date 物件轉換為 LocalDateTime 以便比較
        def transitionDateTime = transitionDate.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
        // 如果轉換時間在我們設定的 startTime 之後，就保留這筆 issue
        return transitionDateTime.isAfter(startTime)
    }
    // 如果沒有找到轉換時間，則不包含此 issue
    return false
}

log.warn("精準過濾後，找到 ${issues.size()} 個符合條件的議題。 ")
if (issues.isEmpty()) {
    log.warn("沒有需要處理的議題，腳本執行結束。 ")
    return
}

// ===================================================================================
// 4. 根據 "Project Reviewer" 欄位對議題進行分組
// ===================================================================================
log.warn("開始根據 '${projectReviewerCfName}' 欄位對議題進行分組...")
def issuesByReviewer = issues.groupBy {
    getSafeCfValue(it, projectReviewerCfName) as ApplicationUser
}
log.warn("議題被分成了 ${issuesByReviewer.size()} 組。 ")

// ===================================================================================
// 5&6. 遍歷每個分組，以產生 Payload, 然後發送數據到遠端 URL
// ===================================================================================
def jiraBaseUrl = ComponentAccessor.getApplicationProperties().getString("jira.baseurl")

// 遍歷每一個審核者 (key) 及其對應的議題列表 (value)
issuesByReviewer.each {
    reviewerUser, issuesInGroup ->
    /* // 檢查 reviewerUser 是否存在。如果某些 issue 的欄位為空，它們會被分到一個 key 為 null 的組裡
    if (!reviewerUser) {
        log.warn("發現 ${issuesInGroup.size()} 個議題沒有設定 '${projectReviewerCfName}'，將跳過這些議題。 ")
        return // 在 .each closure 中 return 相當於 for 迴圈的 continue
    } */
    Long projectReviewerNumericId = reviewerUser.getId()
    log.warn("--- 正在為審核者 '${reviewerUser.getName()}' (ID: ${projectReviewerNumericId}) 準備數據 ---")
    log.warn("該審核者共有 ${issuesInGroup.size()} 個議題需要處理。 ")

    // --- 步驟 A: 針對當前分組的議題，建立 Details 列表 ---
    def detailsList = issuesInGroup.collect {   issue ->
        [
            "itsysdng_issues_type"       : issue.getIssueType()?.getName(),
            "itsysdng_key"               : issue.key,
            "Form_key"                   : "${jiraBaseUrl}/browse/${issue.key}",
            "itsysdng_summary"           : issue.summary,
            "itsysdng_status"            : issue.status?.name,
            "areaString"                 : getJsonSafeString(issue, "Area"),
            "IT_Platform"                : getSafePlatAndModVal(issue, "IT Platform & Module", 0),
            "moduleString"               : getSafePlatAndModVal(issue, "IT Platform & Module", 1),
            "Is_micro_eform"             : getJsonSafeString(issue, "Is Micro-E-form(SFS Form)"),
            "Show_golive"                : getJsonSafeString(issue, "Show on Go-live"),
            "Announcement_plan"          : getJsonSafeString(issue, "Announcement Plan"),
            "plan_golive_date"           : { def d = getSafeCfValue(issue, "Plan Go-live Date") as LocalDateTime; d ? d.format(dateOnlyFormatter) : null }(),
            "itpm"                       : getSafeUserID(issue, "ITPM"),    // 使用ID
            "po"                         : getSafeUserID(issue, "Major Product Owner"),      // 使用ID
            "Is_routinely go-live"       : getJsonSafeString(issue, "Is Routinely go-live"),
            "System_dev_lead"            : getSafeUserID(issue, "System Dev Lead"),         // 使用ID
            "Deployment_owner"           : getSafeUserID(issue, "Deployment Owners"),        // 使用ID
            "Development_Team"           : getJsonSafeString(issue, "Development Team"),    
            "apply_golive_request_date"  : currentTimeFormatted,//{ def d = getSafeCfValue(issue, "Apply Go-live Request Date"); d ? dateTimeFormat.format(d) : null }(),
            "Reason"                     : getJsonSafeString(issue, "Reason")
        ]
    }

    // --- 步驟 B: 建立當前分組的最終 Payload ---
    def payload = [
        forms: [
            Applyer: applyerNumericId.toString(),
            Project_reviewer: "56603341" /* projectReviewerNumericId.toString() */,
            Schedule_job_trigger_date: scheduleJobTriggerDate,
            Details: detailsList
        ]
    ]
    
    // def jsonString = JsonOutput.prettyPrint(JsonOutput.toJson(payload))
    def builder = new JsonBuilder(payload)
    def jsonString = builder.toPrettyString()
    log.warn("--- Prepared JSON Payload for ${reviewerUser.getName()} ---")
    log.warn(jsonString)
    log.warn("--- End of Payload ---")

    // --- 步驟 C: 發送當前分組的數據到遠端 URL ---
    try {
        log.warn("準備發送請求到 ${targetUrl}...")
        def http = new HTTPBuilder(targetUrl)
        
        http.auth.basic(username, password)

        http.request(POST, JSON) {
            body = payload
            headers.'Content-Type' = 'application/json; charset=UTF-8'
            headers.'x-userid' = username
            headers.'x-pwd' = password
            
            // --- 2. 設定如何處理響應 (Response) ---
            // A. 處理請求成功的情況 (HTTP 狀態碼 2xx)
            response.success = { resp, json ->
                log.warn("請求成功！響應結果如下：")
                
                try {
                    def responseBodyString = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json))
                    log.warn("   - 響應內容 (Body):\n${responseBodyString}")
                } catch (Exception e) {
                    log.warn("   - 響應內容 (無法解析為 JSON，原始值): ${json}")
                }

                // --- 提取 OA_NUMBER 並更新議題 ---
                def qaNo = json.forms.OA_NUMBER
                if (qaNo) {
                    String formUrl = "https://dgdvap.deltaww.com/SmartFormV3/#/?FlowCode=2890&InstanceCode=${qaNo}&ToCode=3"
                    log.warn("從響應中提取 OA_NUMBER: ${qaNo}")
                    log.warn("組合完成的表單 URL: ${formUrl}")

                    def urlCfList = customFieldManager.getCustomFieldObjectsByName("Request Go-Live Approval")
                    log.warn("值爲：${urlCfList}")
                    if (urlCfList.isEmpty()) {
                        log.error("找不到名為 'url' 的自定義欄位。 ")
                    } else {
                        def urlCustomField = urlCfList.first()
                        // def issueService = ComponentAccessor.getIssueService()
                        IssueManager issueManager = ComponentAccessor.getIssueManager()


                        issuesInGroup.each { issue ->

                            try {
                                MutableIssue mutableIssue = issueManager.getIssueObject(issue.getId())

                                if (mutableIssue) {
                                    log.warn("準備直接更新議題 ${mutableIssue.key} 的 '${urlCustomField.name}' 欄位...")
                                    
                                    mutableIssue.setCustomFieldValue(urlCustomField, formUrl)
                                    // 使用 issueManager.updateIssue 是比 issue.store() 更現代且推薦的做法
                                    // EventDispatchOption.DO_NOT_DISPATCH 表示不觸發事件，false 表示不發送郵件通知
                                    issueManager.updateIssue(loggedInUser, mutableIssue, EventDispatchOption.DO_NOT_DISPATCH, false)

                                    // 從更新後的 mutableIssue 物件重新讀取欄位值進行驗證
                                    def finalValue = getSafeCfValue(mutableIssue, "Request Go-Live Approval")
                                    log.warn("  - 嘗試寫入的值: ${formUrl}")
                                    log.warn("  - 操作後讀取的值: ${finalValue}")
                                    log.warn("成功執行對議題 ${mutableIssue.key} 的 'Request Go-Live Approval' 欄位的直接更新操作。")

                                }                            
                            } catch (Exception fieldUpdateEx) {
                                log.error("更新議題 ${issue.key} 的 'url' 欄位時發生例外: ${fieldUpdateEx.message}", fieldUpdateEx)
                            }
                        }
                    }
                } else {
                    log.warn("響應中未找到 'OA_NUMBER'，跳過議題欄位更新。 ")
                }
            }

            // B. 處理請求失敗的情況 (HTTP 狀態碼 4xx 或 5xx)
            response.failure = { resp ->
                log.warn("請求失敗！響應結果如下：") 
                def errorBody = resp
                log.warn("   - 響應內容 (Body):\n${resp}")
            }
        }
    } catch (Exception e) {
        // 捕捉網路連線等其他層級的錯誤
        log.warn("發送 HTTP 請求時發生例外錯誤: ${e.message}", e)
    }
}

return