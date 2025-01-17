/*
 *             Twidere - Twitter client for Android
 *
 *  Copyright (C) 2012-2017 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.fragment

import android.accounts.AccountManager
import android.app.Dialog
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.loader.content.Loader
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.analytics.ktx.logEvent
import kotlinx.android.synthetic.main.fragment_status.*
import org.mariotaku.kpreferences.get
import org.mariotaku.kpreferences.set
import org.mariotaku.ktextension.isNullOrEmpty
import org.mariotaku.sqliteqb.library.Expression
import org.mariotaku.twidere.R
import org.mariotaku.twidere.TwidereConstants
import org.mariotaku.twidere.TwidereConstants.NOTIFICATION_ID_HOME_TIMELINE
import org.mariotaku.twidere.annotation.FilterScope
import org.mariotaku.twidere.annotation.ReadPositionTag
import org.mariotaku.twidere.constant.*
import org.mariotaku.twidere.extension.applyTheme
import org.mariotaku.twidere.extension.onShow
import org.mariotaku.twidere.model.ParameterizedExpression
import org.mariotaku.twidere.model.ParcelableStatus
import org.mariotaku.twidere.model.RefreshTaskParam
import org.mariotaku.twidere.model.UserKey
import org.mariotaku.twidere.model.tab.extra.HomeTabExtras
import org.mariotaku.twidere.model.timeline.UserTimelineFilter
import org.mariotaku.twidere.model.util.AccountUtils
import org.mariotaku.twidere.provider.TwidereDataStore.Statuses
import org.mariotaku.twidere.util.DataStoreUtils
import org.mariotaku.twidere.util.ErrorInfoStore
import org.mariotaku.twidere.util.UseStats
import org.mariotaku.twidere.util.UseStats.firebaseLoginstance
import org.mariotaku.twidere.util.popularTweets
import org.mariotaku.twidere.view.FixedTextView
import org.mariotaku.twidere.view.holder.TimelineFilterHeaderViewHolder
import java.util.*


/**
 * Created by mariotaku on 14/12/3.
 */
class HomeTimelineFragment : CursorStatusesFragment() {

    override val errorInfoKey = ErrorInfoStore.KEY_HOME_TIMELINE

    override val contentUri = Statuses.CONTENT_URI

    override val notificationType = NOTIFICATION_ID_HOME_TIMELINE

    override val isFilterEnabled = true

    override val readPositionTag = ReadPositionTag.HOME_TIMELINE

    override val timelineSyncTag: String?
        get() = getTimelineSyncTag(accountKeys)

    override val filterScopes: Int
        get() = FilterScope.HOME

    //drustz : add enable timeline filter on the main feed
    override val enableTimelineFilter: Boolean
        get() = true

    override val timelineFilter: UserTimelineFilter?
        get() = if (enableTimelineFilter) preferences[homeTimelineFilterKey] else null

    override fun updateRefreshState() {
        val twitter = twitterWrapper
        refreshing = twitter.isStatusTimelineRefreshing(contentUri)
    }

    override fun getStatuses(param: RefreshTaskParam): Boolean {
        if (!param.hasMaxIds) return twitterWrapper.refreshAll(param.accountKeys)
        return twitterWrapper.getHomeTimelineAsync(param)
    }

    override fun setUserVisibleHint(isVisibleToUser: Boolean) {
        super.setUserVisibleHint(isVisibleToUser)
        val context = context
        if (isVisibleToUser && context != null) {
            accountKeys.forEach { accountKey ->
                notificationManager.cancel("home_$accountKey", NOTIFICATION_ID_HOME_TIMELINE)
            }
        }

        //drustz: each time the visibility change we collect the time usage
        if (isVisibleToUser) {
            recordEnterTime()
        } else {
            recordLeaveTime()
        }
    }

    private fun recordEnterTime(){
        enterframgmentTimestamp = System.currentTimeMillis()
        readhistoryShownTimestamp = 0
//        Log.d("drz", "onVisible: [home] ??")
    }

    //drustz: record feed view time after the user move out
    private fun recordLeaveTime(){
        if (recyclerView != null) {
            val firstitm = (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            val lastitm = (recyclerView.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()

            for (i in firstitm..lastitm) {
                val contentview = (recyclerView.layoutManager as
                        LinearLayoutManager).findViewByPosition(i)
                val readhistoryView: FixedTextView? = contentview?.findViewById(R.id.lastReadLabel) as FixedTextView?
                if (readhistoryView != null && readhistoryView.tag == "readhistoryshow") {
                    //the read history is already shown in the recycler view.
                    //if the readhistoryshowntimestamp is 0, then it means that
                    // the user did not scroll and the history label is already shown
                    // in the first place. So we need to make it same as the enter time
                    if (readhistoryShownTimestamp == 0.toLong()) {
                        readhistoryShownTimestamp = enterframgmentTimestamp
                    }
                }
            }
        }

        if (enterframgmentTimestamp == 0.toLong()) return

        val usetime = (System.currentTimeMillis() - enterframgmentTimestamp) / 1000
        val timeafterhistory = (System.currentTimeMillis() - readhistoryShownTimestamp) / 1000
//        Log.d("drz", "[HOME] time viewed: "+ usetime)
        if (readhistoryShownTimestamp > 0) {
//            Log.d("drz", "[HOME] time after readhistory: " + timeafterhistory)
            firebaseLoginstance.logEvent("FeedViewTime") {
                param("FeedViewTimeTotal", usetime)
                param("FeedViewTimeAfterHistory", timeafterhistory)
                param("FeedName", "home")
                param("Condition", preferences[expcondition].toLong())
                preferences.getString(TwidereConstants.KEY_PID, "")?.let { param("userID", it) }
            }
        } else {
            firebaseLoginstance.logEvent("FeedViewTime") {
                param("FeedName", "home")
                param("FeedViewTimeTotal", usetime)
                param("Condition", preferences[expcondition].toLong())
                preferences.getString(TwidereConstants.KEY_PID, "")?.let { param("userID", it) }
            }
        }

        enterframgmentTimestamp = 0
        readhistoryShownTimestamp = 0
    }

    //durstz : add header filter for main feed
    override fun processWhere(where: Expression, whereArgs: Array<String>): ParameterizedExpression {
        val arguments = arguments

        if (arguments != null) {
//            val extras = arguments.getParcelable<HomeTabExtras>(EXTRA_EXTRAS)
            val extras = HomeTabExtras().apply {
                isHideQuotes = false
                isHideReplies = false
                isHideRetweets = false
                isHideTweets = false
            }
            //drustz: use filter only when the internal preference set
            if (preferences.getBoolean(TwidereConstants.KEY_INTERNAL_FEATURE, true)) {
                timelineFilter?.let {
                    if (!it.isIncludeReplies) {
                        extras.isHideReplies = true
                    }
                    if (!it.isIncludeRetweets) {
                        extras.isHideRetweets = true
                    }
                    if (!it.isIncludeTweets) {
                        extras.isHideTweets = true
                    }
                }
            }
            if (extras != null) {
                val expressions = ArrayList<Expression>()
                val expressionArgs = ArrayList<String>()
                Collections.addAll(expressionArgs, *whereArgs)
                expressions.add(where)
                DataStoreUtils.processTabExtras(expressions, expressionArgs, extras)
                val expression = Expression.and(*expressions.toTypedArray())
                return ParameterizedExpression(expression, expressionArgs.toTypedArray())
            }
        }
        return super.processWhere(where, whereArgs)
    }

    override fun onLoadFinished(loader: Loader<List<ParcelableStatus>?>, data: List<ParcelableStatus>?) {
        val firstLoad = adapterData.isNullOrEmpty()
        super.onLoadFinished(loader, data)

        try {
            //drustz: save the first item in the load for lastread status
            val firstitm = adapter.getStatus(adapter.statusStartIndex, false)
            val newestTs = preferences[newestTweetTimestampKey]
            var lastreadTs = preferences[lastReadTweetTimestampKey]
            var newstatscnt = 0

            val statscnt = adapter.getStatusCount() - adapter.statusStartIndex
            for (i in adapter.statusStartIndex..statscnt){
                if (adapter.getStatusTimestamp(i) <= newestTs)
                    break
                else newstatscnt += 1
            }

            preferences.edit().apply {
                //only reassign if they are not equal
                if (firstLoad && lastreadTs != newestTs) {
                    this[lastReadTweetTimestampKey] = newestTs
                }
                this[newestTweetTimestampKey] = firstitm.timestamp
            }.apply()
            adapter.lastReadTstamp = preferences[lastReadTweetTimestampKey]
            //drustz: add to stats
            UseStats.modifyStatsKeyCount(preferences, newTweetsStats, newstatscnt)
        } catch (e: IndexOutOfBoundsException) {

        }
    }

    override fun onFilterClick(holder: TimelineFilterHeaderViewHolder) {
        val df = HomeTimelineFilterDialogFragment()
        df.setTargetFragment(this, REQUEST_SET_TIMELINE_FILTER)
        fragmentManager?.let { df.show(it, "set_timeline_filter") }
    }

    override fun onResume() {
        super.onResume()
        if (isVisible && userVisibleHint){
            recordEnterTime()
        }

        //drustz: get trends of the popular tweets
        val details = AccountUtils.getAccountDetails(AccountManager.get(context), accountKeys[0],
                true)?: return
        context?.let {
            popularTweets.getTrends(twitterWrapper, accountKeys[0], preferences[localTrendsWoeIdKey],
                details, it, preferences)
        }
    }

    override fun onPause() {
        super.onPause()
        if (isVisible && userVisibleHint){
            recordLeaveTime()
        }
    }

    override fun readhistoryViewVisible(){
        if (readhistoryShownTimestamp == 0.toLong())
            readhistoryShownTimestamp = System.currentTimeMillis()
    }

    class HomeTimelineFilterDialogFragment : BaseDialogFragment() {

        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            val builder = AlertDialog.Builder(requireContext())
            val values = resources.getStringArray(R.array.values_user_timeline_filter)
            val checkedItems = BooleanArray(values.size) {
                val filter = preferences[homeTimelineFilterKey]
                when (values[it]) {
                    "replies" -> filter.isIncludeReplies
                    "retweets" -> filter.isIncludeRetweets
                    "tweets" -> filter.isIncludeTweets
                    else -> false
                }
            }
            builder.setTitle(R.string.title_user_timeline_filter)
            builder.setMultiChoiceItems(R.array.entries_home_timeline_filter, checkedItems, null)
            builder.setNegativeButton(android.R.string.cancel, null)
            builder.setPositiveButton(android.R.string.ok) { dialog, _ ->
                dialog as AlertDialog
                val listView = dialog.listView
                val filter = UserTimelineFilter().apply {
                    isIncludeRetweets = listView.isItemChecked(values.indexOf("retweets"))
                    isIncludeReplies = listView.isItemChecked(values.indexOf("replies"))
                    isIncludeTweets = listView.isItemChecked(values.indexOf("tweets"))
                }
                preferences.edit().apply {
                    this[homeTimelineFilterKey] = filter
                }.apply()
                (targetFragment as HomeTimelineFragment).reloadStatuses()
            }
            val dialog = builder.create()
            dialog.onShow { it.applyTheme() }
            return dialog
        }

    }

    companion object {
        const val REQUEST_SET_TIMELINE_FILTER = 361
        fun getTimelineSyncTag(accountKeys: Array<UserKey>): String {
            return "${ReadPositionTag.HOME_TIMELINE}_${accountKeys.sorted().joinToString(",")}"
        }

    }
}

