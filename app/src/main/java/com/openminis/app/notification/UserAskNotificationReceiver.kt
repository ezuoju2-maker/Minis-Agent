package com.openminis.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.openminis.app.agent.userask.UserAskCenter
import com.openminis.app.agent.userask.UserAskNotificationPlan
import com.openminis.app.agent.userask.UserAskNotificationPlan.Outcome

class UserAskNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(UserAskNotifier.EXTRA_REQUEST_ID) ?: return
        val index = intent.getIntExtra(UserAskNotifier.EXTRA_INDEX, -1)
        val action = intent.getStringExtra(UserAskNotifier.EXTRA_ACTION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(UserAskNotifier.REMOTE_INPUT_KEY)?.toString()
        val outcome = UserAskCenter.answerFromNotification(requestId, index, action, text)
        if (action == UserAskNotificationPlan.ACTION_OTHER) {
            UserAskNotifier.endReply(UserAskNotificationPlan.tag(requestId, index))
        }
        when (outcome) {
            Outcome.ADVANCED, Outcome.COMPLETE -> Unit
            Outcome.REJECTED, Outcome.STALE -> UserAskNotifier.repost(requestId)
            Outcome.NOT_FOUND -> UserAskNotifier.cancel(UserAskNotificationPlan.tag(requestId, index))
        }
    }
}
