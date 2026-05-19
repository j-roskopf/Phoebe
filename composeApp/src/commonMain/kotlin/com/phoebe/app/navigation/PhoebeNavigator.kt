package com.phoebe.app.navigation

import androidx.navigation3.runtime.NavKey

class PhoebeNavigator(
    val backStack: MutableList<NavKey>,
) {
    val currentRoute: PhoebeRoute
        get() = backStack.last() as PhoebeRoute

    fun open(route: PhoebeRoute) {
        when (route) {
            PhoebeRoute.SignIn,
            PhoebeRoute.ServerPicker,
            PhoebeRoute.LibraryPicker,
            is PhoebeRoute.Browse,
            -> replaceRoot(route)
            PhoebeRoute.Player -> openPlayer()
            else -> {
                if (currentRoute != route) {
                    backStack.add(route)
                }
            }
        }
    }

    fun replaceRoot(route: PhoebeRoute) {
        backStack.clear()
        backStack.add(route)
    }

    fun replaceAll(routes: List<PhoebeRoute>) {
        require(routes.isNotEmpty()) { "Phoebe navigation back stack cannot be empty." }
        backStack.clear()
        backStack.addAll(routes)
    }

    fun openPlayer() {
        if (currentRoute != PhoebeRoute.Player) {
            backStack.add(PhoebeRoute.Player)
        }
    }

    fun pop(): Boolean {
        if (backStack.size <= 1) return false
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    fun canHandleBack(defaultRoute: PhoebeRoute = PhoebeRoute.Browse()): Boolean {
        val route = currentRoute
        return when {
            route is PhoebeRoute.Browse && route.selectedPlaylistId != null -> true
            route is PhoebeRoute.Browse && route.section != BrowseSection.Home -> true
            route == PhoebeRoute.ServerPicker -> true
            route == PhoebeRoute.LibraryPicker -> true
            backStack.size > 1 -> true
            route != defaultRoute -> true
            else -> false
        }
    }

    fun handleBack(defaultRoute: PhoebeRoute = PhoebeRoute.Browse()): Boolean {
        val route = currentRoute
        return when {
            route is PhoebeRoute.Browse && route.selectedPlaylistId != null -> {
                replaceRoot(route.copy(selectedPlaylistId = null))
                true
            }
            route is PhoebeRoute.Browse && route.section != BrowseSection.Home -> {
                replaceRoot(route.copy(section = BrowseSection.Home, selectedPlaylistId = null))
                true
            }
            route == PhoebeRoute.ServerPicker -> {
                replaceRoot(PhoebeRoute.SignIn)
                true
            }
            route == PhoebeRoute.LibraryPicker -> {
                replaceRoot(PhoebeRoute.ServerPicker)
                true
            }
            pop() -> true
            route != defaultRoute -> {
                replaceRoot(defaultRoute)
                true
            }
            else -> false
        }
    }

    fun apply(command: PhoebeNavigationCommand) {
        when (command) {
            is PhoebeNavigationCommand.Open -> open(command.route)
            is PhoebeNavigationCommand.ReplaceRoot -> replaceRoot(command.route)
            is PhoebeNavigationCommand.ReplaceAll -> replaceAll(command.routes)
            PhoebeNavigationCommand.Pop -> pop()
        }
    }
}
