package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.plugins.scm.ImportSynchState
import com.dtolabs.rundeck.plugins.scm.ScmImportSynchState

/**
 * Created by greg on 9/15/15.
 */
class P4ImportSynchState implements ScmImportSynchState {
    int importNeeded
    int notFound
    int deleted
    ImportSynchState state
    String message
}
