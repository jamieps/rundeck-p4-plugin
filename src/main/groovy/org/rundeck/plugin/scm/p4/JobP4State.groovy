package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.plugins.scm.JobState
import com.dtolabs.rundeck.plugins.scm.ScmCommitInfo
import com.dtolabs.rundeck.plugins.scm.SynchState

/**
 * Created by greg on 8/24/15.
 */
class JobP4State implements JobState {
    SynchState synchState
    ScmCommitInfo commit
    List<Action> actions

    @Override
    public String toString() {
        return "JobP4State{" +
                "synchState=" + synchState +
                ", commit=" + commit +
                '}';
    }
}
