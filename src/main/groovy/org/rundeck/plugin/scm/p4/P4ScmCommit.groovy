package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.plugins.scm.ScmCommitInfo

/**
 * Created by greg on 8/28/15.
 */
class P4ScmCommit implements ScmCommitInfo {

    Map mapData

    P4ScmCommit(final Map mapData) {
        this.mapData = new HashMap(mapData)
    }

    @Override
    String getCommitId() {
        mapData?.get('commitId')
    }

    @Override
    String getMessage() {
        mapData?.get("message")
    }

    @Override
    String getAuthor() {
        (mapData?.get("authorName") ?: '') + (mapData?.get("authorEmail") ? '<' + mapData?.get("authorEmail") + '>' :
                '')
    }

    @Override
    Date getDate() {
        mapData?.get("date")
    }

    @Override
    Map asMap() {
        return mapData
    }
}
