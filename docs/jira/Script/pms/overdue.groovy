import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.mail.Email
import com.atlassian.jira.mail.settings.MailSettings
import com.atlassian.mail.MailException
import com.atlassian.mail.server.SMTPMailServer
import com.atlassian.plugin.util.ContextClassLoaderSwitchingUtil
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.link.IssueLink
import com.atlassian.jira.issue.fields.CustomField
import org.apache.log4j.Level
import org.apache.log4j.Logger

import groovy.json.StreamingJsonBuilder
import groovy.json.JsonSlurper
// import groovy.xml.MarkupBuilder

import com.atlassian.jira.issue.search.SearchQuery
import com.atlassian.jira.issue.search.SearchResults
import com.atlassian.jira.jql.parser.JqlQueryParser
// import com.atlassian.jira.issue.search.SearchProvider
import com.atlassian.jira.bc.issue.search.SearchService 
import com.atlassian.jira.web.bean.PagerFilter
import com.atlassian.mail.queue.SingleMailQueueItem
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.user.util.UserManager
import com.atlassian.crowd.embedded.api.Group
import com.atlassian.jira.security.roles.ProjectRoleManager

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.Calendar;
import java.util.stream.Collectors;
import java.text.SimpleDateFormat;

// 這是星期一執行的 job
def dayOfWeek = 1
// def prev_user = null
def prev_proj = null
def prev_proj_name = null

def jira_site = "https://jiradelta.deltaww.com"
// def pms_site = "http://pms-dev.deltaww.com"
def pms_site =    "https://pms.deltaww.com"
def pms_token = "6YPqF49ci2FGCKY4zR1yBSOcwyugitUM"
String authHeaderValue = "Basic amlyYXNlcnZpY2UuYXBpOlBhc3N3b3JkMDE="

class PMSData {
    int id           // 492   
    String projectId // PM0A2
    String notification
    String projectManager
    List<String> pmOfficeList = new ArrayList<String>(); // 2023-07-31

    def getId() {
        return id
    }
    def setId(int id1) {
        id = id1
    }
    def getProjectId() {
        return projectId
    }
    def setProjectId(String id1) {
        projectId = id1
    }
    def getNotification() {
        return notification
    }
    def setNotification(String id1) {
        notification = id1
    }
    def getPmOfficeList() {
        return pmOfficeList
    }
    def setPmOfficeList(List<String> id1) {
        pmOfficeList = id1
    }
    def getProjectManager() {
        return projectManager
    }
    def setProjectManager(String id1) {
        projectManager = id1
    }
}

// 取得當日的數字, 0 代表週日，1 - 6 代表週一 ~ 週六
int getDayOfWeek() {
    Calendar cal = Calendar.getInstance();
    boolean isFirstSunday = (cal.getFirstDayOfWeek() == Calendar.SUNDAY);
    cal.setTime(new Date());

    int i = cal.get(Calendar.DAY_OF_WEEK);
    if(isFirstSunday){
        i = i - 1;
    }
    return i
}

String formatDate(String str) {
    SimpleDateFormat df1 = new SimpleDateFormat("yyyy-MM-dd");
    SimpleDateFormat df2 = new SimpleDateFormat("dd/MMM/yy");
    String str2 = ""   
    if (str != null) {
        Date date1 = df1.parse(str);
        str2 = df2.format(date1);
    }
    return str2
}

Map<String, PMSData> getPMSProject(String site, String token) {
    
    def logger = Logger.getLogger("com.acme.getPMSProject")
    logger.setLevel(Level.DEBUG)

    Map<String, PMSData> proj_data = new HashMap<>();
    def url_path = "/api/JIRA/projects?t="
    // def token = "CM9LPXasOKD8D4faECZOirrqGPhSfDBA"
    def baseURL = site + url_path + token
    log.debug(" [getPMSProject.baseURL] : " + baseURL);

    URL url = new URL(baseURL)
    HttpURLConnection connection = url.openConnection() as HttpURLConnection
    connection.setRequestMethod("GET")
    connection.setDoOutput(true)
    //connection.setRequestProperty("Authorization", authHeaderValue)
    connection.connect()

    // Handle response
    def res_code = connection.getResponseCode()
    log.debug(" [getPMSProject.res_code] : " + res_code);

    def buff_r = new BufferedReader(new InputStreamReader((connection.getInputStream())))
    def rtn_body = new StringBuilder()
    String rtn_line
    while ((rtn_line = buff_r.readLine()) != null) {
      rtn_body.append(rtn_line)
    }
    def str_rb = rtn_body.toString()

    def rtn_json_obj = new JsonSlurper().parseText(str_rb)
    // def rc_code = rtn_json_obj.code
    // log.debug(" [getPMSProject.rc_code] : " + rc_code);
    def proj_list = rtn_json_obj.data 
    
    int i = 0;
    proj_list.each { 
        // List<Integer> days = new ArrayList<>();
        PMSData data = new PMSData();
        def projId = it.projectID
        def id = it.id
        def noticeList = it.notification
        def pmOfficeList = it.pmOffice
        def projectManager = it.projectManager

        if (noticeList != null) {
            String nlist = ""
            noticeList.each { 
                // days.add((Integer) it); 
                nlist += it + ","
            }    
            data.setProjectId(projId)
            data.setId(id)
            data.setNotification(nlist)
            data.setProjectManager(projectManager)
        } 

        if (pmOfficeList != null) {
            int ipos = pmOfficeList.indexOf(",")
            if (ipos != -1) {
                List<String> convertedList = Arrays.asList(pmOfficeList.split(",", -1));
                data.getPmOfficeList().addAll(convertedList)
            } else {
                data.getPmOfficeList().add(pmOfficeList)
            }
        }

        proj_data.put(projId as String, data)
        // log.info ("[] getPMSProject = " + projId as String)
        i++
    }

    return proj_data;
}    

String getEMailByName(String jiraSite, String userName, String authHeaderValue) {

    if(userName == null || userName.equals("") || userName.equalsIgnoreCase("null"))
        return ""

    String rc = ""
    
    // API Post
    // def site = "https://jiradev.deltaww.com"
    def url_path = "/rest/api/2/user?username="
    def baseURL = jiraSite + url_path + userName
    log.debug(" [baseURL] : " + baseURL);

    URL url = new URL(baseURL)
    HttpURLConnection connection = url.openConnection() as HttpURLConnection
    connection.setRequestMethod("GET")
    connection.setDoOutput(true)
    connection.setRequestProperty("Authorization", authHeaderValue)
    connection.connect()

    try {
        // Handle response
        def res_code = connection.getResponseCode()

        def buff_r = new BufferedReader(new InputStreamReader((connection.getInputStream())))
        def rtn_body = new StringBuilder()
        String rtn_line
        while ((rtn_line = buff_r.readLine()) != null) {
        rtn_body.append(rtn_line)
        }
        def str_rb = rtn_body.toString()

        def rtn_json_obj = new JsonSlurper().parseText(str_rb)
        def email_addr = rtn_json_obj.emailAddress
        
        if (!email_addr) {
            log.info ("[] email_addr not found !!!")
            // rc = "[] email_addr not found !!!"
        } else {
            log.info ("[] email_addr = " + email_addr as String)
            rc = email_addr as String
            rc = rc.toLowerCase()
            // throw new InvalidInputException("Status Error",
        // "There is an existed Confluence Space Name same as Project Name you input, please change the project name!")
        }
    } catch(Exception ex) {
        log.error ("[] ERROR :" + ex.getMessage())
    }
    return rc;
}    

void sendEmail2(List<String> emailTo, List<String> cc, String subject, String body) {

    if (emailTo.size() == 0)
        return;

    SMTPMailServer mailServer = ComponentAccessor.getMailServerManager().getDefaultSMTPMailServer();    
    if (mailServer) {        
        Email email = new Email(String.join(",",emailTo))        
        email.setSubject(subject)        
        email.setBody(body)        
        email.setMimeType("text/html")
        if (cc.size() != 0)
            email.setCc(String.join(",", cc))
        // email.setBcc(bcc)        
        // def item = new SingleMailQueueItem(email)        
        // ComponentAccessor.getMailQueue().addItem(item)    
        mailServer.send(email) // 2023-08-15, bug fix
    } else 
        log.info ("[] sendEmail2 : fail to find mail server !!!")
}

String buildHTML(List<Map<String, String>> issuelist, String jira_site, String pms_site, PMSData pdata) {

    // <a href='https://pms-qas.deltaww.com/#/projectdetail/597/form'>PM0CG-測RATIONAL---</a> 
    def pmsurl = "<a href='" + pms_site + "/#/projectdetail/" + pdata.getId() + "/form'>" + pdata.getProjectId() + "</a>"
    def html = "<head>"
    html += "<style>"
    html += ".cell-highlight { "
    html += "  background-color: #ccffcc; "
    html += "  font-weight: bold; "
    html += "}"
    html += "</style>"
    html += "</head>"
    html += "<body>"
    html += "<article>"
    html += "  <section>"
    html += "  <p>Hi,</p>"
    html += "  <p>The following are the " + pmsurl + " overdue tasks or will be overdue within 7 days. A kind reminder to discuss with the project manager and PMO and take necessary actions accordingly. Thank you </p>"
    html += "  </section>"
    html += "  <p>您好,</p>"
    html += "  <p>您參與 " + pmsurl + " 專案，以下為該專案已過期或即將於7天內過期之工作項目，請盡速更新工作狀態，若需要調整請與專案經理聯繫 </p>"
    html += "  <section>"
    html += "  </section>"
    html += "</article>"  

    html += "<table style=\"border-collapse: collapse; width: 101.459%;\" border='1'>"
    html += "<tbody>"
    html += "<tr style=\"border: solid thin;\" >"
    html += "<td class='cell-highlight' style=\"width: 10%;\">Key</td>"
    html += "<td class='cell-highlight' style=\"width: 20%;\">Summary</td>"
    html += "<td class='cell-highlight' style=\"width: 10%;\">Assignee</td>"
    html += "<td class='cell-highlight' style=\"width: 10%;\">Status</td>"
    // html += "<td style=\"width: 10%;\">Updated</td>"
    html += "<td class='cell-highlight' style=\"width: 10%;\">Due</td>"
    html += "<td class='cell-highlight' style=\"width: 10%;\">Description</td>"
    html += "</tr>"

    for (Map is : issuelist) {

        // def fieldName = "Due"
        // CustomField customField_due = customFieldManager.getCustomFieldObjects(is).find {it.name == fieldName}
        // String due = is.getCustomFieldValue(customField_due)

        def key = is.get("key")
        def summary = is.get("summary")
        def assignee = is.get("assignee")
        def status = is.get("status")
        // def updated = is.get("updated")
        def due = is.get("due")
        def desc = is.get("desc")
        def issue_url = "<a href='" + jira_site + "/browse/" + key + "'>" + key + "</a>"

        html += "<tr style=\"border: solid thin;\" >"
        html += "<td style=\"width: 14.2857%;\">" + issue_url + "</td>"
        html += "<td style=\"width: 25.9964%;\">" + summary + "</td>"
        html += "<td style=\"width: 17.7256%;\">" + assignee + "</td>"
        html += "<td style=\"width: 14.2857%;\">" + status + "</td>"
        // html += "<td style=\"width: 14.2857%;\">" + updated + "</td>"
        html += "<td style=\"width: 14.2857%;\">" + due + "</td>"
        html += "<td style=\"width: 15.7445%;\">" + desc + "</td>"
        html += "</tr>"
    }

    html += "</tbody>"
    html += "</table>"
    html += "<article>"
    html += "  <section>"
    html += "  <p>** This is an automated PMS (Project Management System) email. Please don't reply to this email. For more PMS information please contact <a href='mailto:G-Delta-PMCoE@deltaww.com'>G-Delta-PMCoE@deltaww.com</a>.**</p>"
    html += "  </section>"
    html += "</article>"  

    return html;
}

String handleAssignee(String assignee) {
    if (assignee != null) {
        // assignee 處理, REMOVE (
        def i = assignee.indexOf("(");
        if (i != -1) {
            assignee = assignee.substring(0, i);
        }
    }
    return assignee;    
}

Map<String, String> getIssueValue(Issue issue) {
    Map<String, String> tmpmap = new HashMap<String, String>();

    def key = issue.key as String
    def summary = issue.summary as String
    def tmpassignee = issue.assignee as String
    def status = issue.getStatus().getSimpleStatus().getName()
    def updated = issue.updated as String
    def due = issue.getDueDate() as String
    def desc = issue.description as String

    tmpassignee = handleAssignee(tmpassignee)
    if (desc == null)
        desc = ""
    updated = formatDate(updated)    
    due = formatDate(due)
    
    tmpmap.put("key", key)
    tmpmap.put("summary", summary)
    tmpmap.put("assignee", tmpassignee)
    tmpmap.put("status", status)
    tmpmap.put("updated", updated)
    tmpmap.put("due", due)
    tmpmap.put("desc", desc)

    return tmpmap
}

def getDifferencesUser(List<ApplicationUser> list1, List<ApplicationUser> list2) {
    List<String> differences = new ArrayList<>(list1);
    differences.removeAll(list2);
    return differences
}

def getUsersByGroupName(String groupName){
    def groupManager = ComponentAccessor.getGroupManager()
    def userManager = ComponentAccessor.getUserManager()

    def usersInGroup1 = groupManager.getUsersInGroup(groupName)
    Collection<ApplicationUser> Group1 = usersInGroup1
    return Group1
}

def getUsersForSpecifiedRolesInProject(String projectName, String role){
    def projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager.class)
    def projectRole = projectRoleManager.getProjectRole(role)
    def project = ComponentAccessor.getProjectManager().getProjectObjByName(projectName)
    def usersInRole = projectRoleManager.getProjectRoleActors(projectRole, project).getApplicationUsers().toList()
    return usersInRole
}

def log = Logger.getLogger("com.acme.pmsOverDueMail")
log.setLevel(Level.ERROR)

def jqlQueryParser = ComponentAccessor.getComponent(JqlQueryParser)
// def searchProvider = ComponentAccessor.getComponent(SearchProvider)
def searchService = ComponentAccessor.getComponent(SearchService)
def issueManager = ComponentAccessor.issueManager
def issueLinkManager = ComponentAccessor.getIssueLinkManager()
def customFieldManager = ComponentAccessor.getCustomFieldManager()

def serviceUser = ComponentAccessor.getUserManager().getUserByName("jiraservice.api")
ComponentAccessor.getJiraAuthenticationContext().setLoggedInUser(serviceUser);
def user = ComponentAccessor.getJiraAuthenticationContext().getUser()

Map<String, PMSData> pmsmap = getPMSProject(pms_site, pms_token)
// List<Integer> tmplist = pmsmap.get("123")
List<String> pmsKeys = new ArrayList<String>(pmsmap.keySet());
String pmsIDs = ""
List<HashMap<String, String>> issuelist = new ArrayList<>();

// 設定 dayOfWeek
dayOfWeek = getDayOfWeek()
log.debug(" dayOfWeek : " + dayOfWeek)

// TODO : REMOVE THIS CODE !!!
// dayOfWeek = 2
// log.debug(" dayOfWeek : " + dayOfWeek)

pmsKeys.each { 
    // def tmpdays = pmsmap.get(it)
    // if (tmpdays.contains(dayOfWeek)) {
    //     pmsIDs += "\""+ it +"\""
    //     pmsIDs += ","
    // }
    PMSData tmpdays = pmsmap.get(it)

    if (tmpdays.getNotification() == null)
        return

    // log.debug(" getNotification : " + tmpdays.getNotification())
    if (tmpdays.getNotification().indexOf("" + dayOfWeek) != -1) {
        pmsIDs += " \""+ it +"\""
        pmsIDs += " ,"
    }
}

log.debug(" pmsIDs : " + pmsIDs)
if (pmsIDs.length() > 1)
    pmsIDs = pmsIDs.substring(0, pmsIDs.length() -1) // 去掉最後的 ,
else {
    log.debug(" Fail to find pms ID, exit !!!")
    return
}

// def query_str = "category = PMS and status not in (Done, Cancelled) and due <=7d AND project in ( _pms_id_ ) AND assignee IS NOT NULL ORDER BY assignee ASC , project ASC, due ASC"
// 加上 AND assignee IS NOT NULL 避免取得 assignee 為 NULL 的資料
def query_str = "category = PMS and status not in (Done, Cancelled) and due <=7d AND project in ( _pms_id_ ) AND assignee IS NOT NULL ORDER BY project ASC, due ASC"
query_str = query_str.replace('_pms_id_', pmsIDs)

/**/
def query = jqlQueryParser.parseQuery(query_str)
SearchResults results = searchService.search(serviceUser, query, PagerFilter.getUnlimitedFilter())
log.debug("Total issues: ${results.total}")

def jira_adminList = getUsersByGroupName("jira-administrators")
// for (ApplicationUser user1 : jira_adminList){
//     log.debug("jira admin: " + user1.getName())
// }

def idList = []
def k = 0
def emailList = []

List<String> ccList = new ArrayList<>();
List<String> issueKeyList = new ArrayList<>();
List<String> emailList2 = new ArrayList<>();
List<String> assigneelist = new ArrayList<>();

results.getResults().each { documentIssue ->

    def issue = issueManager.getIssueObject(documentIssue.id)
    def proj = issue?.getProjectObject()
    def assignee = issue.assignee as String
    def issueKey = issue.key as String
    def projName = proj.name as String
    def projKey = proj.key as String
    
    // log.debug(" [] projKey : " + projKey + ", issueKey : " + issueKey + " , assignee : " + assignee)

    if (assignee != null) {
        assignee = handleAssignee(assignee)

        if (prev_proj == null) {
            prev_proj = projKey 
            prev_proj_name = projName
            log.debug(" [] init prev_proj : " + projKey)
        }

        if (prev_proj != null && prev_proj.equalsIgnoreCase(projKey)) {
            Map<String, String> tmpmap = getIssueValue(issue)
            // log.debug(" [] tmpmap : " + tmpmap)
            issuelist.add(tmpmap)
            issueKeyList.add(issueKey)
            assigneelist.add(assignee)

            // 取得專案的 admin 
            // def userList = getUsersForSpecifiedRolesInProject(prev_proj_name, "Administrators")
            // 減掉 jira-administrators
            // def differencesUser = getDifferencesUser(userList, jira_adminList)
            // for (ApplicationUser user1 : differencesUser){
            //     // if (k < 7) // TODO : REMOVE THIS CODE
            //     // log.debug("prev_proj_name : $prev_proj_name, admin : " + user1.getName())
            //     admin_mail = getEMailByName(jira_site, user1.getName(), authHeaderValue)
            //     ccList.add(admin_mail)
            // }
        } 

        // log.debug(" prev_proj : " + prev_proj + ", projKey : " + projKey)

        if (prev_proj != null && !prev_proj.equalsIgnoreCase(projKey)) {
            // 印出 issueKeyList
            // for(String key : issueKeyList)
            //     log.debug(" issueKeyList : " + key)

            // 取得專案的 admin 
            // def userList = getUsersForSpecifiedRolesInProject(prev_proj_name, "Administrators")
            // 減掉 jira-administrators
            // def differencesUser = getDifferencesUser(userList, jira_adminList)
            // for (ApplicationUser user1 : differencesUser){
            //     // if (k < 7) // TODO : REMOVE THIS CODE
            //     admin_mail = getEMailByName(jira_site, user1.getName(), authHeaderValue)
            //     // log.debug("prev_proj_name : $prev_proj_name, admin : " + user1.getName() + ", admin mail :" + admin_mail)
            //     ccList.add(admin_mail)
            // }

            PMSData pdata = pmsmap.get(prev_proj)

            // 取得專案的 admin , 2023-07-31
            for(String userT : pdata.getPmOfficeList()) {
                def mail1 = getEMailByName(jira_site, userT, authHeaderValue)
                if (mail1 != ""){
                    log.debug("prev_proj_name : $prev_proj_name, admin : " + userT + ", admin mail :" + mail1)
                    ccList.add(mail1)
                }
            }
            def pmMail = getEMailByName(jira_site, pdata.getProjectManager(), authHeaderValue)
            if (pmMail != ""){
                log.debug("prev_proj_name : $prev_proj_name, pm mail :" + pmMail)
                ccList.add(pmMail)
            }
            

            def source = buildHTML(issuelist, jira_site, pms_site, pdata)

            // if (k < 10) {
            // log.debug(" prev_proj : " + prev_proj + ", projKey : " + projKey + " assignee : " + assignee)
            // } 

            // 將 assigneelist 展開並放到 emailList , 2022-06-08
            for( String user1 : assigneelist) {
                String user_mail1 = getEMailByName(jira_site, user1, authHeaderValue)
                if (user_mail1 != ""){
                    // log.debug(" user_mail : " + user_mail1)
                    emailList2.add(user_mail1) 
                }
            }            
            // 移除重覆資料, TODO : REMOVE MARK
            emailList = emailList2.stream()
            .distinct()
            .collect(Collectors.toList());
            log.debug(" emailList : " + emailList)

            // 移除重覆資料, TODO : REMOVE MARK
            List<String> ccList2 = ccList.stream()
            .distinct()
            .collect(Collectors.toList());
            log.debug(" ccList2 : " + ccList2)
            
            // user_mail = getEMailByName(jira_site, prev_user)
            // log.debug(" user_mail : " + user_mail)
            // emailList.add(user_mail) 

            // TODO : REMOVE TEST CODE
            // ccList2 = new ArrayList<String>()
            // emailList = new ArrayList<String>()
            // emailList.add("edwardwen8910@gmail.com")    
            // ccList2.add("edward.wen@deltaww.com")
            // ccList2.add("edward.wen@deltaww.com")

            // 五碼專案編號-專案名稱
            // title = ' [PMS] "' + prev_proj + "-" + prev_proj_name + '" Task Overdue Notice' // 修正 project key重複問題
            title = ' [PMS] "' + prev_proj_name + '" Task Overdue Notice'

            log.debug(" title : " + title)
            // TODO : REMOVE THIS MARK
            sendEmail2(emailList, ccList2, title, source)   

            // reset
            assigneelist = new ArrayList<>();
            // prev_user = assignee
            prev_proj = projKey  
            prev_proj_name = projName
            issuelist = new ArrayList<>()
            assigneelist.add(assignee)
            emailList2 = new ArrayList<>();
            issueKeyList = new ArrayList<>();
            
            Map<String, String> tmpmap = getIssueValue(issue)
            issuelist.add(tmpmap)        
            issueKeyList.add(issueKey)        
            ccList = new ArrayList<>();
            // 取得專案的 admin 
            // userList = getUsersForSpecifiedRolesInProject(prev_proj_name, "Administrators")
            // 減掉 jira-administrators
            // differencesUser = getDifferencesUser(userList, jira_adminList)
            // for (ApplicationUser user1 : differencesUser){
            //     // if (k < 7) // TODO : REMOVE THIS CODE
            //     // log.debug("prev_proj_name : $prev_proj_name, admin : " + user1.getName())
            //     admin_mail = getEMailByName(jira_site, user1.getName(), authHeaderValue)
            //     ccList.add(admin_mail)
            // }              
        }

        if (k == (results.total-1)) {
            // 將最後一筆印出
            log.debug(" [" + k + "] 將最後一筆印出")
            prev_proj = projKey  
            prev_proj_name = projName
            // def ccList = [] 

            // 印出 issueKeyList
            // for(String key : issueKeyList)
            //     log.debug(" last issueKeyList : " + key)

            // 取得專案的 admin 
            // def userList = getUsersForSpecifiedRolesInProject(prev_proj_name, "Administrators")
            // 減掉 jira-administrators
            // def differencesUser = getDifferencesUser(userList, jira_adminList)
            // for (ApplicationUser user1 : differencesUser){
            //     // if (k < 7) // TODO : REMOVE THIS CODE
            //     log.debug("prev_proj_name : $prev_proj_name, admin : " + user1.getName())
            //     admin_mail = getEMailByName(jira_site, user1.getName())
            //     ccList.add(admin_mail)
            // }

            PMSData pdata = pmsmap.get(prev_proj)

            // 取得專案的 admin , 2023-07-31
            for(String userT : pdata.getPmOfficeList()) {
                def mail1 = getEMailByName(jira_site, userT, authHeaderValue)
                if (mail1 != ""){
                    log.debug("prev_proj_name : $prev_proj_name, admin : " + userT + ", admin mail :" + mail1)
                    ccList.add(mail1)
                }
            }
            def pmMail = getEMailByName(jira_site, pdata.getProjectManager(), authHeaderValue)
            if (pmMail != ""){
                log.debug("prev_proj_name : $prev_proj_name, pm mail :" + pmMail)
                ccList.add(pmMail)
            }

            def source = buildHTML(issuelist, jira_site, pms_site, pdata)

            // if (k < 10) {
                // log.debug(" last prev_proj : " + prev_proj + ", projKey : " + projKey)
                // log.debug(" last assignee : " + assignee)
            // } 

            // user_mail = getEMailByName(jira_site, prev_user)
            // log.debug(" user_mail : " + user_mail)
            // emailList.add(user_mail)   
            
            // assigneelist 展開並放到 emailList , 2022-06-08
            // emailList2 = new ArrayList<>();
            for( String user1 : assigneelist) {
                String user_mail1 = getEMailByName(jira_site, user1, authHeaderValue)
                if (user_mail1 != ""){
                    // log.debug(" last user_mail : " + user_mail1)
                    emailList2.add(user_mail1) 
                }
            }
            // 移除重覆資料, TODO : REMOVE MARK
            emailList = emailList2.stream()
            .distinct()
            .collect(Collectors.toList());
            log.debug(" latest emailList : " + emailList)

            // 移除重覆資料, TODO : REMOVE MARK
            List<String> ccList2 = ccList.stream()
            .distinct()
            .collect(Collectors.toList());
            log.debug(" latest ccList2 : " + ccList2)

            // 五碼專案編號-專案名稱
            // title = ' [PMS] "' + prev_proj + "-" + prev_proj_name + '" Task Overdue Notice' // 修正 project key重複問題
            title = ' [PMS] "' + prev_proj_name + '" Task Overdue Notice'
            log.debug(" latest title : " + title)

            // TODO : REMOVE TEST CODE
            // ccList2 = new ArrayList<String>()
            // emailList = new ArrayList<String>()
            // emailList.add("edwardwen8910@gmail.com")    
            // ccList2.add("edward.wen@deltaww.com")
            // ccList2.add("edward.wen@deltaww.com")

            // TODO : REMOVE THIS MARK
            sendEmail2(emailList, ccList2, title, source)        
        }
    }
    k++
}    