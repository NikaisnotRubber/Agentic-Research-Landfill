import org.apache.log4j.Logger
import org.apache.log4j.Level
import java.util.*;
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.CustomFieldManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.component.ComponentAccessor

def logger = Logger.getLogger("com.acme.syncsprint")
logger.setLevel(Level.INFO)

String getHostType() {
    def baseurl = com.atlassian.jira.component.ComponentAccessor.getApplicationProperties().getString("jira.baseurl")
    if (baseurl.contains("jiradev")) {
        return "dev"
    } 
    else if (baseurl.contains("jirastage")) {
        return "stage"
    } 
    else if (baseurl.contains("jiradelta")) {
        return "prod"
    } 
    else {
        return null
    }
}

// Project Attribute 若非 Global Project，則不指派 Program Manager
def customField = ComponentAccessor.getCustomFieldManager().getCustomFieldObject("customfield_19210")
def projectAtr = issue.getCustomFieldValue(customField)?.toString()

logger.info('=========='+projectAtr)

if (projectAtr == "Regional Project") {
    return
}

def hostType = getHostType()
Long corpStrategyId
Long programManagerId

// 正式環境與 STAGE 環境的欄位 ID
if (hostType == "prod"){
    corpStrategyId = 19420
    programManagerId = 19421
}
else if (hostType == "stage"){
    corpStrategyId = 19420
    programManagerId = 19421
}
else {
    logger.error("Host type is not Prod or Stage!")
    return
}

logger.info ("Host Type: " + hostType)

// Corp Strategy & Program 與 PM 的對應表
def mappingTable = [
    "Immersive Experience Creator": "Singyu.Liu",
    "Opportunity Lifecycle Management": "SAM.SHIH",
    "Smart e-Learning": "Hongyi.Du",
    "Solution E2E": "SAM.SHIH",
    "XR guidance": "Jack.SC.Chiu",
    "Supply Chain Management & Transportation Management": "TONY.IC.LIN",
    "Smart Quality Management System": "TONY.IC.LIN",
    "Manufacturing Operations Management/MES Ecosystems": "OWEN.YH.CHEN",
    "MES AIOpS": "NAT.CHEN",
    "AIoT Modernization and Unification": "DA.ZHANG",// 2025/04/02
    "AIoT Architecture and Design": "CLARENCE.CHIEN",// 2025/04/02
    "Digital Factory Operation Center": "JIAN.CHENG",// 2025/04/02
    "Digital Manufacturing Platform" :  "JIAN.CHENG",// 2025/04/02
    "RD Software License Management": "BEN.SC.CHANG",// 2025/04/29
    "Digital Engineering Process": "June.YC.Chen",
    "Digital Workplace": "Shengru.Xiao",
    "Product Data Management": "Allen.Jiang",
    "Digital Engineering Process": "June.YC.Chen",
    "Smart Design": "Xinyuan.Yan",
    "ITSCR Policy & Auditing": "JENNY.TSEN",
    "ITSCR Risk Management": "Frankie.Chen",
    "Security Enhancement": "Daniel.CL.Chang",
    "Smart Operation - Security": "Stanley.Lin",
    "AI & AUTOMATION": "Steve.Jung",
    "API Management": "BECKY.HM.WANG",
    "Data Government": "Steve.Jung",
    "Data Analytics": "Boris.Chuang",
    "Delta Device MGT": "Frank.Yang",
    "End to End System Availability": "Vicky.Kuo",
    "End to End Project Availability": "Vicky.Kuo",
    "Process Automation": "Jason.Pai",
    "PLM Platform": "RAY.BH.CHIU",
    "Software Asset Management": "Dino.Lin",
    "S/4 HANA": "Zoe.HY.Lee",
    "Training Development and Operation": "YUNGJOU.WANG",
    "M&A Integration": "TERRY.CHANG",
    "Infra Well-Architected_Server": "GRAY.LEE",
    "Infra Well-Architected_Networking": "ANDERSON.CHANG",
    "Infra Well-Architected_Data Center": "MK.JIAU",
    "Infra Well-Architected_Database": "GX.DENG",
    "Infra Well-Architected_Infra Archi": "FRED.CW.CHOU",
    // "Other": "Xinyuan.Yan"
]

def corpStrategyVal = issue.getCustomFieldValue(corpStrategyId) as Map
def programManagerVal

String firstVal = corpStrategyVal.get(null)
String lastVal = corpStrategyVal.get("1")

logger.info("Option1: ${firstVal}, Option2: ${lastVal}")

if (mappingTable.containsKey(lastVal)) {
    if (firstVal == "Smart Design" && lastVal == "Others"){
        programManagerVal = Users.getByName("Xinyuan.Yan")
    }
    else {
        programManagerVal = Users.getByName(mappingTable[lastVal])
    }
}
else {
    logger.error("Program Manager not found!")
}

logger.info("Program Manager: ${programManagerVal}")

// 指派 Program Manager
def customFieldManager = ComponentAccessor.getCustomFieldManager()
def programManager = customFieldManager.getCustomFieldObject(programManagerId)
issue.setCustomFieldValue(programManager, programManagerVal)
