package dev.accountguard

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import androidx.room.Room
import com.topjohnwu.superuser.Shell
import dev.accountguard.data.db.PolicyDatabase
import dev.accountguard.data.model.GoogleAccount
import dev.accountguard.service.AccountPolicyViewModel
import dev.accountguard.service.MainViewModel
import dev.accountguard.service.PolicyRepository
import dev.accountguard.ui.screens.*
import dev.accountguard.ui.theme.AccountGuardTheme

// ─────────────────────────────────────────────────────────────
// APPLICATION CLASS
// ─────────────────────────────────────────────────────────────

class AccountGuardApp : Application() {

    lateinit var database: PolicyDatabase
        private set

    lateinit var repository: PolicyRepository
        private set

    override fun onCreate() {
        super.onCreate()

        // Initialize libsu Shell for root operations
        // KernelSU-Next is fully compatible with libsu
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )

        // Initialize Room database
        database = Room.databaseBuilder(
            applicationContext,
            PolicyDatabase::class.java,
            PolicyDatabase.DATABASE_NAME
        ).build()

        repository = PolicyRepository(database)
    }
}

// ─────────────────────────────────────────────────────────────
// MAIN ACTIVITY
// ─────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AccountGuardApp

        setContent {
            AccountGuardTheme {
                AccountGuardNavHost(
                    mainViewModelFactory = MainViewModelFactory(app),
                    policyViewModelFactory = AccountPolicyViewModelFactory(app)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// NAVIGATION
// ─────────────────────────────────────────────────────────────

@Composable
fun AccountGuardNavHost(
    mainViewModelFactory: MainViewModelFactory,
    policyViewModelFactory: AccountPolicyViewModelFactory
) {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel(factory = mainViewModelFactory)

    // We track the selected account for the policy screen
    var selectedAccount by remember { mutableStateOf<GoogleAccount?>(null) }

    NavHost(
        navController = navController,
        startDestination = "main",
        enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(280)) },
        exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(280)) },
        popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(280)) },
        popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(280)) }
    ) {
        composable("main") {
            MainScreen(
                viewModel = mainViewModel,
                onAccountSelected = { account ->
                    selectedAccount = account
                    navController.navigate("policy")
                },
                onNavigateToLogs = {
                    navController.navigate("logs")
                }
            )
        }

        composable("policy") {
            val account = selectedAccount
            if (account != null) {
                policyViewModelFactory.account = account
                val policyViewModel: AccountPolicyViewModel = viewModel(
                    key = "policy_${account.name}",
                    factory = policyViewModelFactory
                )
                AccountPolicyScreen(
                    account = account,
                    viewModel = policyViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable("logs") {
            val logs by mainViewModel.recentLogs.collectAsState()
            LogsScreen(
                logs = logs,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────
// VIEW MODEL FACTORIES
// ─────────────────────────────────────────────────────────────

class MainViewModelFactory(private val app: AccountGuardApp) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(app, app.repository) as T
    }
}

class AccountPolicyViewModelFactory(private val app: AccountGuardApp) : ViewModelProvider.Factory {
    var account: GoogleAccount? = null

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val acc = account ?: error("Account must be set before creating AccountPolicyViewModel")
        @Suppress("UNCHECKED_CAST")
        return AccountPolicyViewModel(app, app.repository, acc) as T
    }
}
