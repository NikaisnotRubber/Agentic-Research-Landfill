import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager
import com.atlassian.jira.project.Project
import org.apache.log4j.Level
import org.apache.log4j.Logger

// --- 設定區 (請修改以下變數) ---

// 1. 來源 Issue Type Scheme 的 **確切** 名稱
final String sourceSchemeName = "PM000: Project Management Issue Type Scheme"

// 2. 目標 Issue Type Scheme 的 **確切** 名稱
final String targetSchemeName = "PM000: Project Management Issue Type Scheme 2.0"

// 3. 安全開關：設為 true 僅會模擬執行並印出日誌，不會真的修改。
//    確認日誌無誤後，請改為 false 再執行一次來實際變更。
final boolean dryRun = false

log.info("腳本開始執行...")
if (dryRun) {
    log.warn("=== 正在以 DRY RUN (模擬執行) 模式運行 ===")
    log.warn("=== 不會對專案進行任何實際的變更 ===")
}
// 初始化 issueTypeScheme 和 Project 的 managet object
def issueTypeSchemeManager = ComponentAccessor.getIssueTypeSchemeManager()
def projectManager = ComponentAccessor.getProjectManager()

// **修正部分：獲取所有 schemes 然後用 find 找到目標**
def allSchemes = issueTypeSchemeManager.getAllSchemes()
def sourceScheme = allSchemes.find { it.name == sourceSchemeName }
def targetScheme = allSchemes.find { it.name == targetSchemeName }

// 檢查 Scheme 是否存在
if (!sourceScheme) {
    log.error("錯誤：找不到來源 Issue Type Scheme: '${sourceSchemeName}'。請檢查名稱是否正確。")
    return "腳本因錯誤中止"
}

if (!targetScheme) {
    log.error("錯誤：找不到目標 Issue Type Scheme: '${targetSchemeName}'。請檢查名稱是否正確。")
    return "腳本因錯誤中止"
}

log.info("成功找到來源 Scheme: '${sourceScheme.name}' (ID: ${sourceScheme.id})")
log.info("成功找到目標 Scheme: '${targetScheme.name}' (ID: ${targetScheme.id})")

if (sourceScheme.id == targetScheme.id) {
    log.error("錯誤：來源和目標 Scheme 是同一個。無需執行變更。")
    return "腳本因錯誤中止"
}
//  取得關聯的所有 projects (使用 attribute associatedProjectObjects )
List<Project> associateProjects = sourceScheme.associatedProjectObjects


if (associateProjects.isEmpty()) {
    log.info("沒有找到任何關聯到來源 Scheme '${sourceSchemeName}' 的專案，無需執行任何操作。")
} else {
    log.info("預計將以下 ${associateProjects.size()} 個專案從 Scheme '${sourceSchemeName}' 變更為 '${targetSchemeName}':")
    // log.info("Project Keys: ${projectKeysToChange.join(', ')}")

    if (!dryRun) {
        try {
            log.info("正在執行一次性批次更新...")
            // **使用您建議的 addProjectAssociations 進行一次性批次更新** (interface FieldConfigScheme, Collection<Project>)
            issueTypeSchemeManager.addProjectAssociations(targetScheme, associateProjects)
            log.info(">>> 成功完成批次更新！")
        } catch (Exception e) {
            log.error("!!! 在執行批次更新時發生錯誤: ${e.message}", e)
        }
    } else {
        log.warn("[DRY RUN] >>> 模擬執行，未進行實際變更。")
    }
}

log.info("--- 腳本執行完畢 ---")
return "腳本執行完成，請查看日誌以獲取詳細資訊。"