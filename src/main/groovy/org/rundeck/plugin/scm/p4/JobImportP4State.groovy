package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.plugins.scm.ImportSynchState
import com.dtolabs.rundeck.plugins.scm.JobImportState
import com.dtolabs.rundeck.plugins.scm.ScmCommitInfo

/**
 * Created by greg on 9/14/15.
 */
class JobImportP4State implements JobImportState {
    ImportSynchState synchState

    ScmCommitInfo commit

    List<Action> actions

    @Override
    public String toString() {
        return "JobImportP4State{" +
                "state=" + synchState +
                ", commit=" + commit +
                '}';
    }
}
