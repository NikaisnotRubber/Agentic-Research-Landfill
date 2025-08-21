/*  
    conn external db  
*/ 
import groovy.sql.Sql
import java.sql.Driver
import com.atlassian.jira.component.ComponentAccessor
import com.onresolve.scriptrunner.db.DatabaseUtil

// init driver obj
def driver = Class.forName('com.mysql.jdbc.Driver').newInstance() as Driver

def customFieldManager = ComponentAccessor.getCustomFieldManager()

String sql_stmt = "SELECT projectID, projectName, projectDescription, startDate, endDate, projectManager, steeringCommittee, projectTeam, projectObjective, projectScope FROM jira_pms_portal WHERE projectID = '__PROJID__'"

sql_stmt = sql_stmt.replace('__PROJID__', val_proj_pms)

    def res = []

try {
    pmsdb.eachRow(sql_stmt) { row ->
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
}finally {
    pmsdb.close()
    conn.close()
}

def rows = DatabaseUtil.withSql('ITSWER-1478_RTCAPI') { sql ->
    sql.rows(sql_stmt)
}
