package org.sunbird.job.fixture

object EvenFixture {
    
    val EVENT_1: String = """{"eid": "BE_JOB_REQUEST","ets": 1621833310477,"mid": "LP.1621833310477.429e795f-6d3e-4a11-8754-685984d62d10","actor": {"id": "Course Certificate Generator","type": "System"},"context": {"pdata": {"ver": "1.0","id": "org.sunbird.platform"}},"object": {"id": "0131000245281587206_do_11309999837886054416","type": "CourseCertificateGeneration"},"edata": {"userIds": ["user001"],"action": "issue-certificate","iteration": 1, "trigger": "auto-issue","batchId": "0131000245281587206","reIssue": true,"courseId": "do_11309999837886054415"}}"""
    val USER_1: String = """{"id":".private.user.v1.read.c4cc494f-04c3-49f3-b3d5-7b1a1984abad","ver":"private","ts":"2021-05-27 10:25:05:836+0000","params":{"resmsgid":null,"msgid":"8e27cbf5-e299-43b0-bca7-8347f7e5abcf","err":null,"status":"success","errmsg":null},"responseCode":"OK","result":{"response":{"firstName":"user","lastName":"name","rootOrg":{"keys":{}}}}}"""
    val CONTENT_1: String = """{"id":"api.content.read","ver":"3.0","ts":"2021-05-27T10:31:33ZZ","params":{"resmsgid":"316b05dd-df1a-4867-968a-042bb06a710f","msgid":null,"err":null,"status":"successful","errmsg":null},"responseCode":"OK","result":{"content":{"objectType":"Content","primaryCategory":"Course","contentType":"Course","identifier":"do_11309999837886054416","languageCode":["en"],"name":"test Course"}}}"""

}
