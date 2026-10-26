package com.bistu.allthebistu.ui.schedule

import android.app.Application
import com.bistu.allthebistu.data.log.AppLog
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bistu.allthebistu.data.LoadResult
import com.bistu.allthebistu.data.ScheduleRepository
import com.bistu.allthebistu.data.Source
import com.bistu.allthebistu.data.auth.JwxtApi
import com.bistu.allthebistu.data.net.RetryPolicy
import com.bistu.allthebistu.data.privacy.UserDataCleaner
import com.bistu.allthebistu.ui.login.AuthHolder
import com.bistu.allthebistu.ui.login.AutoLoginResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 课表页的全部状态。
 *
 * 只放「跨重组需要活下来」的东西（数据、加载态、登录态），
 * 纯交互态（当前 Tab、是否展开设置页、Pager 页码）留在 Compose 里。
 */
data class ScheduleUiState(
    /** 课表数据 + 来源 + 时间戳；null 表示还没加载出结果 */
    val loadResult: LoadResult? = null,
    /** 首次加载中（整页转圈） */
    val isLoading: Boolean = true,
    /** 下拉刷新中（顶部指示器，不遮挡内容） */
    val isRefreshing: Boolean = false,
    /** 自动登录没成功的原因，展示在标题下方 */
    val autoLoginHint: String? = null,
    /** 是否展示底部登录引导条 */
    val showLoginPrompt: Boolean = false,
    /** 当前是否已登录（与 AuthHolder 同步） */
    val isLoggedIn: Boolean = false,
    /** 学业完成情况（要求学分/已修/已选），没拿到时为 null */
    val studyReport: com.bistu.allthebistu.data.StudyReport? = null,
    /** 学业报告是否正在刷新 */
    val reportLoading: Boolean = false
)

/**
 * 课表 ViewModel。
 *
 * 从 MainActivity 里搬出来的那些状态与协程，现在都收在这里：
 *  - 自动登录 + 首次加载
 *  - 手动重新加载 / 下拉刷新（含最短展示时长）
 *  - 会话失效清理
 *  - 登录状态变化后的强制刷新
 *
 * Activity 只负责画界面和把点击事件转发过来。
 */
class ScheduleViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ScheduleRepository(app)

    /** 学业报告的本地缓存（文件 + JSON），退出登录时会被清掉 */
    private val reportStore = com.bistu.allthebistu.data.StudyReportStore(app)

    private val _uiState = MutableStateFlow(ScheduleUiState())
    val uiState: StateFlow<ScheduleUiState> = _uiState.asStateFlow()

    /** 当前正在跑的加载任务；新的加载会取消旧的，避免两次结果互相覆盖 */
    private var loadJob: Job? = null

    /** 上一次的登录状态，用于识别「刚登录成功」这个跳变 */
    private var lastKnownLoggedIn: Boolean? = null

    init {
        // 会话失效 → 清理持久化登录态（回调发生在 IO 线程，切回主线程再动 Compose 状态）
        repository.onSessionExpired = {
            viewModelScope.launch { handleSessionExpired() }
        }
        bootstrap()
    }

    // ───────────────────────────────────────────────────────────
    // 启动：缓存秒开 → 恢复会话 → 后台静默刷新
    // ───────────────────────────────────────────────────────────
    private fun bootstrap() {
        viewModelScope.launch {
            // ① 先把上次同步的课表亮出来。
            // 这一步不碰网络：没网、教务系统宕机、机房断电，
            // 用户打开 App 也能立刻看到自己的课表，而不是转圈等待。
            val cached = repository.loadCached()
            if (cached != null) {
                _uiState.update {
                    it.copy(
                        loadResult = cached,
                        isLoading = false,
                        // 有缓存时用顶部指示器表示「正在后台同步」，不再整页转圈
                        isRefreshing = true
                    )
                }
            }

            // 先显示上次抓到的学业报告（离线也能看）
            reportStore.load()?.let { cachedReport ->
                _uiState.update { it.copy(studyReport = cachedReport) }
            }

            // ② 恢复登录态
            val result = AuthHolder.autoLogin(getApplication())
            AppLog.d(TAG, "自动登录结果: $result")
            AuthHolder.auth?.let(repository::setAuth)
            lastKnownLoggedIn = AuthHolder.isLoggedIn

            when (result) {
                is AutoLoginResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoggedIn = AuthHolder.isLoggedIn,
                            autoLoginHint = null,
                            // 没有缓存才整页 loading，有缓存就保持界面可见
                            isLoading = cached == null,
                            isRefreshing = cached != null
                        )
                    }
                    // 已登录 → 跳过缓存读，直接拉最新；失败会自动回退到刚显示的缓存
                    val loaded = loadWithRetry(forceRefresh = true)
                    // 这次同步可能刚用 TGC 换过新票（JwxtApi.ensureSession 会静默续期），
                    // 把新 Cookie 落盘，下次冷启动就不用再换一遍
                    if (loaded.source == Source.NETWORK) {
                        runCatching { AuthHolder.persist(getApplication()) }
                    }
                    _uiState.update {
                        it.copy(loadResult = loaded, isLoading = false, isRefreshing = false)
                    }
                    refreshStudyReport()
                }

                is AutoLoginResult.NeedManual -> {
                    _uiState.update {
                        it.copy(
                            isLoggedIn = AuthHolder.isLoggedIn,
                            autoLoginHint = result.reason,
                            // 未登录 → 每次启动都提示一次（不记忆、不打扰已登录用户）
                            showLoginPrompt = true,
                            isLoading = cached == null,
                            isRefreshing = false
                        )
                    }
                    // 有缓存就先用缓存（说明是「会话过期」而不是「从没登录过」），
                    // 没有缓存才回落到示例数据
                    if (cached == null) {
                        val loaded = repository.loadSchedule()
                        _uiState.update { it.copy(loadResult = loaded, isLoading = false) }
                    }
                }
            }
        }
    }

    // ───────────────────────────────────────────────────────────
    // 对外操作
    // ───────────────────────────────────────────────────────────

    /**
     * 加载课表，失败时补一次重试。
     *
     * 网络层（[JwxtApi]）已经按请求重试过抖动类失败；这一层管的是另一件事：
     * 「整次加载快速失败」往往是环境刚变化 —— 刚从 4G 切回校园 Wi-Fi、
     * 刚解锁、上网认证刚跳完。这种失败隔一两秒再来一次通常就好了，
     * 而直接弹「同步失败」会让用户以为得手动处理。
     *
     * 只补「快速失败」：慢失败说明网络真的不通，再等一轮只是把
     * 转圈时间翻倍（见 [RetryPolicy.shouldRetryUi]）。
     */
    private suspend fun loadWithRetry(forceRefresh: Boolean): LoadResult {
        val startedAt = System.currentTimeMillis()
        val first = repository.loadSchedule(forceRefresh = forceRefresh)
        val error = first.error ?: return first
        val elapsed = System.currentTimeMillis() - startedAt
        if (!RetryPolicy.shouldRetryUi(error, elapsed)) return first

        AppLog.d(
            TAG,
            "加载失败（${error.javaClass.simpleName}: ${error.message}，耗时 ${elapsed}ms），" +
                    "${UI_RETRY_DELAY_MS}ms 后自动重试一次"
        )
        delay(UI_RETRY_DELAY_MS)
        return repository.loadSchedule(forceRefresh = forceRefresh)
    }

    /**
     * 重新加载。
     *
     * @param forceRefresh true 时先清缓存再拉，用于「刚登录」「退出登录」这类数据源已变的场景
     */
    fun reload(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            AuthHolder.auth?.let(repository::setAuth)
            // 注意：这里**不再**先 clearCache 再拉。
            // 之前「登录后强制刷新」会先清空缓存，一旦这次网络请求失败，
            // 用户就从「有真实课表」直接掉到「示例数据」，看起来像数据丢了。
            // forceRefresh 的语义是「跳过缓存读」，而不是「删掉缓存」，
            // 拉取成功时 saveSchedule 会整学期覆盖，用不着提前清。
            val loaded = loadWithRetry(forceRefresh = forceRefresh)
            _uiState.update { it.copy(loadResult = loaded, isLoading = false) }
        }
    }

    /**
     * 下拉刷新：保留当前内容，只在顶部显示指示器。
     */
    fun refresh() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            val startedAt = System.currentTimeMillis()
            try {
                AuthHolder.auth?.let(repository::setAuth)
                // 下拉 = 强制从网络拉最新
                val loaded = loadWithRetry(forceRefresh = true)
                _uiState.update { it.copy(loadResult = loaded) }
                refreshStudyReport()
            } finally {
                // 本地数据（如未登录读 assets）几毫秒就返回，
                // 指示器会一闪而过看不到动画。补足最短显示时长，
                // 让「出现 → 转一圈 → 回缩」完整播完。
                val remaining = MIN_REFRESH_MS - (System.currentTimeMillis() - startedAt)
                if (remaining > 0) delay(remaining)
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    /**
     * 登录状态变化（由界面观察到 AuthHolder 变化后转发进来）。
     *
     * 只在「未登录 → 已登录」这个跳变时强制刷新，避免每次重组都重复请求。
     */
    fun onAuthChanged(loggedIn: Boolean) {
        val previous = lastKnownLoggedIn
        lastKnownLoggedIn = loggedIn

        _uiState.update {
            it.copy(
                isLoggedIn = loggedIn,
                showLoginPrompt = if (loggedIn) false else it.showLoginPrompt
            )
        }

        if (previous == false && loggedIn) {
            _uiState.update { it.copy(autoLoginHint = null) }
            reload(forceRefresh = true)
        }
    }

    /**
     * 退出登录：清掉本机所有个人信息，回落到示例数据。
     *
     * 清除范围见 [UserDataCleaner]：登录凭据（Cookie / 记住的学号）、
     * 课表缓存（含学期信息里的姓名学号专业）、应用临时文件，
     * 以及各业务模块注册进来的扩展数据（后续的校园卡、学籍等）。
     *
     * 注意第一步先断内存里的登录态：即使后面的磁盘清理出问题，
     * 也不会出现「界面还显示登录中」的状态。
     */
    fun logout() {
        viewModelScope.launch {
            AuthHolder.clear(getApplication())
            repository.clearAuth()
            lastKnownLoggedIn = false
            _uiState.update {
                it.copy(
                    isLoggedIn = false,
                    autoLoginHint = null,
                    showLoginPrompt = false,
                    isLoading = true
                )
            }

            val cleared = UserDataCleaner(getApplication()).wipeAll()
            AppLog.d(TAG, "退出登录已清除: ${cleared.joinToString("、")}")

            val loaded = repository.loadSchedule()
            _uiState.update {
                it.copy(loadResult = loaded, isLoading = false, studyReport = null)
            }
        }
    }

    /**
     * 拉取学业完成情况（要求学分 / 已修 / 已选）。
     *
     * 失败只记日志，不弹错误——学业数据属于「锦上添花」，
     * 挂了不该影响课表本身的使用。
     */
    fun refreshStudyReport() {
        val auth = AuthHolder.auth ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(reportLoading = true) }
            runCatching { JwxtApi(auth).fetchStudyReport() }
                .onSuccess { report ->
                    reportStore.save(report)
                    _uiState.update { it.copy(studyReport = report, reportLoading = false) }
                    AppLog.d(TAG, "学业报告: 已修 ${report.creditsEarned}/${report.creditsRequired}，" +
                            "已选 ${report.creditsSelection}")
                }
                .onFailure { e ->
                    AppLog.w(TAG, "学业报告获取失败: ${e.message}")
                    _uiState.update { it.copy(reportLoading = false) }
                }
        }
    }

    /** 用户关掉底部登录引导条，或已经点了「去登录」 */
    fun dismissLoginPrompt() {
        _uiState.update { it.copy(showLoginPrompt = false) }
    }

    // ───────────────────────────────────────────────────────────
    // 内部
    // ───────────────────────────────────────────────────────────
    private suspend fun handleSessionExpired() {
        // ① 先断内存登录态：后面清理磁盘即使出问题，界面也不会停留在「已登录」
        AuthHolder.clear(getApplication())
        repository.clearAuth()
        lastKnownLoggedIn = false

        // ② 个人信息必须和登录态一起清。
        //    以前这里只清了 Cookie，头像 / 昵称 / 学业报告 / 课表缓存全留在磁盘上，
        //    于是界面变成「已经退出登录，头像还是本人的照片」——看起来像没退干净。
        //    手动点「退出登录」走 UserDataCleaner 是干净的，自动过期这条路径漏了。
        runCatching { UserDataCleaner(getApplication()).wipeAll() }
            .onSuccess { AppLog.d(TAG, "会话过期：已清理 ${it.joinToString("、")}") }
            .onFailure { AppLog.w(TAG, "会话过期：清理个人数据失败 ${it.message}") }

        // ③ 明确告诉用户「为什么掉线」，而不是静默变成未登录
        _uiState.update {
            it.copy(
                isLoggedIn = false,
                loadResult = null,
                studyReport = null,
                reportLoading = false,
                autoLoginHint = "登录已过期，请重新登录",
                showLoginPrompt = true,
                isLoading = false,
                isRefreshing = false
            )
        }
    }

    companion object {
        private const val TAG = "ScheduleViewModel"

        /**
         * 下拉刷新指示器的最短显示时长（毫秒）。
         *
         * 800ms 是 Material Design 建议的「既能感知到、又不觉得卡」的时长。
         */
        private const val MIN_REFRESH_MS = 800L

        /** 整次加载失败后自动重试前的等待（毫秒） */
        private const val UI_RETRY_DELAY_MS = 2_000L
    }
}
