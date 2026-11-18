package com.dekstream.tv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsFootball
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.dekstream.tv.data.Category
import com.dekstream.tv.data.Channel
import com.dekstream.tv.data.Game
import com.dekstream.tv.data.PlayRequest
import com.dekstream.tv.data.Series
import com.dekstream.tv.data.Vod

enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Live("Live TV", Icons.Default.LiveTv),
    Guide("Guide", Icons.Default.CalendarViewWeek),
    Movies("Movies", Icons.Default.Movie),
    Series("Series", Icons.Default.Tv),
    Sports("Sports", Icons.Default.SportsFootball),
    Multiview("Multiview", Icons.Default.GridView),
    Search("Search", Icons.Default.Search),
    Settings("Settings", Icons.Default.Settings),
}

sealed interface Route {
    data class MovieDetail(val vod: Vod) : Route
    data class SeriesDetail(val series: Series) : Route
    data class VodCategory(val category: Category, val isSeries: Boolean) : Route
    data class GameDetail(val game: Game) : Route
    data object SportsSettings : Route
    data class Player(val request: PlayRequest, val zapList: List<Channel> = emptyList()) : Route
}

/** App-wide navigation state: a selected tab plus a stack of pushed screens. */
class AppState {
    var tab by mutableStateOf(Tab.Home)
    val stack = mutableStateListOf<Route>()

    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean = if (stack.isNotEmpty()) { stack.removeAt(stack.lastIndex); true } else false

    fun play(req: PlayRequest, zapList: List<Channel> = emptyList()) = push(Route.Player(req, zapList))
}
