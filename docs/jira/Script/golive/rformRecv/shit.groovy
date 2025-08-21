import groovy.sql.Sql
import java.sql.Driver
import com.atlassian.jira.component.ComponentAccessor
import com.onresolve.scriptrunner.db.DatabaseUtil
import org.apache.log4j.Level
import org.apache.log4j.Logger

def log = Logger.getLogger("com.acme.updateProjectWithPMSInfo")
log.setLevel(Level.DEBUG)

def driver = Class.forName('com.mysql.jdbc.Driver').newInstance() as Driver

def customFieldManager = ComponentAccessor.getCustomFieldManager()
def obj_select_pms = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Had Registered on PMS Portal?' }
String val_select_pms = issue.getCustomFieldValue(obj_select_pms)
def rc = false

log.debug(" issue.key : " + issue.key);
log.debug(" [val_select_pms] : " + val_select_pms);

if ('Yes'.equalsIgnoreCase(val_select_pms)) {
    def userManager = ComponentAccessor.getUserManager()
    
    def obj_proj_pms = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Project on PMS Portal' }
    def val_proj_pms = issue.getCustomFieldValue(obj_proj_pms)
	log.debug(" [val_proj_pms] : " + val_proj_pms);
    
    // def props = new Properties()
    // props.setProperty('user', 'pmsdb_r_usr')
    // props.setProperty('password', 'efhj56!rkl*P')
    // def conn = driver.connect('jdbc:mysql://localhost/PMSDB', props)
    // def pmsdb = new Sql(conn)

    //def rowNum = 0
    // String sql_stmt = '''SELECT
    //                 projectID,
    //                 projectName,
    //                 projectDescription,
    //                 startDate,
    //                 endDate,
    //                 projectManager,
    //                 steeringCommittee,
    //                 projectTeam,
    //                 projectObjective,
    //                 projectScope
    //               FROM PMS_Projects_B
    //               WHERE projectID = "__PROJID__"'''

    String sql_stmt = "SELECT projectID, projectName, projectDescription, startDate, endDate, projectManager, steeringCommittee, projectTeam, projectObjective, projectScope FROM jira_pms_portal WHERE projectID = '__PROJID__'"

    sql_stmt = sql_stmt.replace('__PROJID__', val_proj_pms)

    def res = []

    // try {
    //     pmsdb.eachRow(sql_stmt) { row ->
    //         def record = [:]
    //         record['projectID'] = row[0]
    //         record['projectName'] = row.projectName
    //         record['projectDescription'] = row.projectDescription
    //         record['projectObjective'] = row.projectObjective
    //         record['projectScope'] = row.projectScope
    //         record['startDate'] = row.startDate
    //         record['endDate'] = row.endDate
    //         record['projectManager'] = row.projectManager
    //         record['steeringCommittee'] = row.steeringCommittee
    //         record['projectTeam'] = row.projectTeam
    //         res.add(record)
    //         log.info (" PMS PROJ NAME : " + row.projectName as String)
    //         rc = true
    //     }
	// }finally {
    //     pmsdb.close()
    //     conn.close()
    // }

    def rows = DatabaseUtil.withSql('ITSWER-1478_RTCAPI') { sql ->
        sql.rows(sql_stmt)
    }

    //now get each sql row and create a table row
    rows.each{ row ->
        def record = [:]
        record['projectID'] = row[0]
        record['projectName'] = row.projectName
        record['projectDescription'] = row.projectDescription
        record['projectObjective'] = row.projectObjective
        record['projectScope'] = row.projectScope
        record['startDate'] = row.startDate
        record['endDate'] = row.endDate
        record['projectManager'] = row.projectManager
        record['steeringCommittee'] = row.steeringCommittee
        record['projectTeam'] = row.projectTeam
        res.add(record)
        log.info (" PMS PROJ NAME : " + row.projectName as String)
        rc = true
    }

    def obj_proj_number = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'PMS Project Number' }
    def obj_proj_name = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Project Name' }
    def obj_proj_desc = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Project Description' }

    def obj_key_benifit = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Key Benefits' }
    def obj_proj_init = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Plan Initiation Date' }
    def obj_proj_end = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Plan Go-live Date' }

    def obj_proj_pm = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Biz PM(s)' }
    def obj_proj_steering = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Steering Committee' }

    def obj_proj_bpartner = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Business Partners' }

    def obj_req_issue = customFieldManager.getCustomFieldObjects(issue).find { it.name == 'Request Issuer' }

    def proj_info = res[0]
    def pm_list = []
    def steering_list = []
    def p_team_list = []

    if (proj_info != null && proj_info['projectManager'] != null)
        for (u in proj_info['projectManager'].split(',')) {
            pm_list.add(userManager.getUserByName(u))
        }

    if (proj_info != null && proj_info['steeringCommittee'] != null)
        for (u in proj_info['steeringCommittee'].split(',')) {
            steering_list.add(userManager.getUserByName(u))
        }

    if (proj_info != null && proj_info['projectTeam'] != null)
        for (u in proj_info['projectTeam'].split(',')) {
            p_team_list.add(userManager.getUserByName(u))
        }

    String oscop = ""
    if (proj_info != null && proj_info['projectObjective'] != null && proj_info['projectScope'] != null)
        oscop = proj_info['projectObjective'] + '\n' + proj_info['projectScope']
    //pms: projectID  <=> PMS Project Number
    //pms: projectObjective + projectScope <=> Key Benefits
    //pms: projectManager <=> Biz PM(s)
    //pms: projectTeam <=> Business Partners
    //pms: projectManager <=> Request Issuer
    if (rc) {
        issue.setCustomFieldValue(obj_proj_number, proj_info['projectID'])
        issue.setCustomFieldValue(obj_proj_name, proj_info['projectName'])
        issue.setCustomFieldValue(obj_proj_desc, proj_info['projectDescription'])
        issue.setCustomFieldValue(obj_key_benifit, oscop)
        issue.setCustomFieldValue(obj_proj_init, proj_info['startDate'])
        issue.setCustomFieldValue(obj_proj_end, proj_info['endDate'])
        issue.setCustomFieldValue(obj_proj_pm, pm_list)
        issue.setCustomFieldValue(obj_proj_steering, steering_list)
        issue.setCustomFieldValue(obj_proj_bpartner, p_team_list)
        issue.setCustomFieldValue(obj_req_issue, pm_list[0])
    } else 
        log.error (" Fail to find DB record, skip to update data !!!")        
}

//def user = ComponentAccessor.getJiraAuthenticationContext().getLoggedInUser()
//issueManager.updateIssue(user, issue, EventDispatchOption.ISSUE_UPDATED, false)

return true



{"forms":{"Applyer":"@020","Project_reviewer":"56603341","Schedule_job_trigger_date":"2025/8/13~2025/8/20","Details":[{"itsysdng_issues_type":"IT System - Dev","itsysdng_key":"PMSPG-1","Form_key":"https://jirastage.deltaww.com/browse/PMSPG-1","itsysdng_summary":"Test_by_Alvis_0513","itsysdng_status":"Go-Live Approval","areaString":"A-TW 台灣","IT_Platform":"SFSOTH-微表單其他","moduleString":"2182-ISO文件發佈申請單","Is_micro_eform":"Yes","Show_golive":"Yes","Announcement_plan":"No","plan_golive_date":"2025/8/22","itpm":"V-GAOBUGAO.GAO 高步寶","po":"V-GAOBUGAO.GAO 高步寶","Is_routinely go-live":"Yes ( Mon, Tue, Wed, Thu)","System_dev_lead":"DANNY.SHU 徐雲鵬 Danny.Shu","Deployment_owner":"DANNY.SHU 徐雲鵬, VICKY.KUO 郭姿岑","Development_Team":"AIOT and MFG IT System Development Dept","apply_golive_request_date":"2025/8/20 17:34","Reason":null}]}}