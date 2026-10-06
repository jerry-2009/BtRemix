package com.Fusion.Btremix.melody.hook.anchor

import android.appwidget.AppWidgetProvider
import android.content.ContentProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The single source of truth for every host anchor the module hooks
 * (`HANDOFF_MELODY_M6_PLAN.md` §1, revised after the first real host update).
 *
 * Anchor ids are stable and are what appears in `melody.anchor.*` events, the `melody_anchor_report_v1`
 * preference and the update prompt. Do not rename them.
 *
 * Matcher notes (all shapes are taken from the 17.6.3 dex, see `docs/melody-capability-map.md` §9):
 *  - framework/`com.oplus.melody`-stable types are the strongest handles (R8 cannot rename them);
 *  - the *recorded packages* are kept for documentation/reporting only - the DexKit scan is global,
 *    because the short packages (`c7`, `D0`, `A9`, `n7`, `Ba`, `i9`) are renamed every release too.
 */
internal object MelodyAnchorCatalog {

    // --- transport suppression (M3.4) --------------------------------------------------------------

    const val TRANSPORT_CLIENT = "transport.client"
    const val TRANSPORT_WRITE = "transport.write"

    // --- ANC write redirects (M5.1/M5.2/M5.3) ------------------------------------------------------

    const val REDIRECT_V0 = "redirect.v0"
    const val REDIRECT_V0_RESULT = "redirect.v0.result"
    const val REDIRECT_SETGATE = "redirect.setgate"
    const val REDIRECT_PROVIDER = "redirect.provider"

    // --- detail panel (M4.2) -----------------------------------------------------------------------

    const val PANEL_GROUP_OBSERVER = "panel.group_observer"
    const val PANEL_DEVICE_CONTROL_WIDGET = "panel.device_control_widget"
    const val PANEL_PREFERENCE_CATEGORY = "panel.preference_category"
    const val PANEL_COUI_MENU_PREFERENCE = "panel.coui_menu_preference"

    // --- registry / DTOs (M3.4, M4.3) --------------------------------------------------------------

    const val BTSDK_DEVICE_INFO_MANAGER = "btsdk.device_info_manager"
    const val BTSDK_DEVICE_INFO = "btsdk.device_info"
    const val BTSDK_NOISE_INFO = "btsdk.current_noise_mode_info"
    const val DTO_EARPHONE = "dto.earphone"

    // --- providers / whitelist (M3.3, M3.4b, M4.1) -------------------------------------------------

    const val PROVIDER_ALIVE = "provider.melody_alive"
    const val PROVIDER_MY_DEVICE = "provider.my_device"
    const val DTO_WHITELIST_CONFIG = "dto.whitelist_config"
    const val DTO_WHITELIST_CONTENT = "dto.whitelist_content"
    const val WHITELIST_REPO_MAPPER = "whitelist.repo_mapper"
    const val WHITELIST_REPO_IMPL = "whitelist.repo_impl"
    const val WHITELIST_UTILS = "whitelist.utils"

    // --- device card / refresh (M5.1 follow-up) ----------------------------------------------------

    const val CARD_SENDER = "card.data_sender"
    const val CARD_VO = "card.data_vo"
    const val CARD_MENU_BUILDER = "card.menu_builder"
    const val CARD_MENU_SDK_MANAGER = "card.menu_sdk_manager"
    const val CARD_WIDGET_PROVIDER = "card.widget_provider"
    const val ANC_REFRESH_ITEM = "anc.refresh_item"
    const val ANC_REFRESH_ONE_SPACE = "anc.refresh_one_space"
    const val ANC_REFRESH_VO = "anc.refresh_vo"
    const val EARPHONE_REPOSITORY = "repo.earphone"

    // --- wirelesssettings branch (M3.4c) -----------------------------------------------------------

    const val SETTINGS_PODS_DATA_MANAGER = "settings.pods_data_manager"

    private const val EARPHONE_PKG = "com.oplus.melody.model.repository.earphone"
    private const val BTSDK_MANAGER_PKG = "com.oplus.melody.btsdk.api.manager"
    private const val BTSDK_DATA_PKG = "com.oplus.melody.btsdk.api.data"
    private const val MY_DEVICES_PKG = "com.oplus.melody.mydevices.devicecard"
    private const val WHITELIST_PKG = "com.oplus.melody.common.data"
    private const val WHITELIST_UTIL_PKG = "com.oplus.melody.common.util"
    private const val SETGATE_PKG = "com.oplus.melody.mydevices.devicecard.noisereduction"
    private const val PROVIDER_PKG = "com.oplus.melody.provider"
    private const val DEVICE_CONTROL_PKG = "com.oplus.melody.ui.widget.devicecontrol"
    private const val NOISE_ITEM_PKG = "com.oplus.melody.ui.component.detail.noisereduction"

    private fun of(clazz: Class<*>) = MelodyTypeRef.Of(clazz)
    private fun named(name: String) = MelodyTypeRef.Named(name)
    private fun anchor(id: String) = MelodyTypeRef.AnchorRef(id)
    private fun shape(
        name: String?,
        params: List<MelodyTypeRef?> = emptyList(),
        returnType: MelodyTypeRef? = null,
    ) = MelodyMethodShape(name, params, returnType)

    val all: List<MelodyAnchorSpec> = listOf(
        MelodyAnchorSpec(
            id = TRANSPORT_CLIENT,
            host = MelodyAnchorHost.Melody,
            feature = "transport.client",
            baselineClasses = listOf("c7.b"),
            packages = listOf("c7"),
            // `BRClientDevice.o(UUID)`: the only `(UUID) -> void` declaration in the host once the
            // `androidx.media3` DRM constructor of the same shape is excluded by the denylist.
            query = MelodyAnchorQuery.MethodSignature(null, listOf(of(UUID::class.java)), of(Void.TYPE)),
            methodAnchor = true,
        ),
        MelodyAnchorSpec(
            id = TRANSPORT_WRITE,
            host = MelodyAnchorHost.Melody,
            feature = "transport.write",
            baselineClasses = listOf("d7.a"),
            packages = listOf("d7"),
            // `BaseBRConnection.d(byte[], byte[], long)`: unique in the host, declared on an abstract class.
            query = MelodyAnchorQuery.MethodSignature(
                null,
                listOf(of(ByteArray::class.java), of(ByteArray::class.java), of(Long::class.javaPrimitiveType!!)),
                of(Void.TYPE),
            ),
            methodAnchor = true,
            allowAbstract = true,
        ),
        MelodyAnchorSpec(
            id = REDIRECT_V0,
            host = MelodyAnchorHost.Melody,
            feature = "redirect.v0",
            baselineClasses = listOf("$EARPHONE_PKG.EarphoneRepositoryClientImpl", "$EARPHONE_PKG.J"),
            packages = listOf(EARPHONE_PKG),
            query = MelodyAnchorQuery.MethodSignature(
                "v0",
                listOf(of(Int::class.javaPrimitiveType!!), of(String::class.java)),
                of(CompletableFuture::class.java),
            ),
            methodAnchor = true,
            allowMultiple = true,
        ),
        MelodyAnchorSpec(
            id = REDIRECT_V0_RESULT,
            host = MelodyAnchorHost.Melody,
            feature = "redirect.v0.return_dto",
            baselineClasses = listOf("$EARPHONE_PKG.O"),
            packages = listOf(EARPHONE_PKG),
            query = MelodyAnchorQuery.MethodName("getSetCommandStatus", 0),
        ),
        MelodyAnchorSpec(
            id = REDIRECT_SETGATE,
            host = MelodyAnchorHost.Melody,
            feature = "redirect.setgate",
            baselineClasses = listOf("$SETGATE_PKG.NoiseReductionCommand"),
            packages = listOf(SETGATE_PKG),
            query = MelodyAnchorQuery.MethodSignature(
                "onReceive",
                listOf(of(Context::class.java), of(Intent::class.java)),
                of(Void.TYPE),
            ),
            methodAnchor = true,
        ),
        MelodyAnchorSpec(
            id = REDIRECT_PROVIDER,
            host = MelodyAnchorHost.Melody,
            feature = "redirect.provider",
            baselineClasses = listOf("$PROVIDER_PKG.EarphoneControlProvider"),
            packages = listOf(PROVIDER_PKG),
            query = MelodyAnchorQuery.MethodSignature(
                "call",
                listOf(of(String::class.java), of(String::class.java), of(Bundle::class.java)),
                of(Bundle::class.java),
            ),
            methodAnchor = true,
        ),
        MelodyAnchorSpec(
            id = PANEL_GROUP_OBSERVER,
            host = MelodyAnchorHost.Melody,
            feature = "panel.model",
            baselineClasses = listOf("A9.f"),
            packages = listOf("A9"),
            // `A9.f` is one R8-merged synthetic Observer: many `(Object) -> void` methods, one of which
            // is `onChanged`. The runtime guard in the hook already ignores anything that is not the
            // `<group key> -> List<String>` model, so a few candidates are acceptable - they just pass
            // through.
            query = MelodyAnchorQuery.Structural(
                methods = listOf(shape("onChanged", listOf(of(Any::class.java)), of(Void.TYPE))),
                methodCountMin = 8,
            ),
            allowMultiple = true,
        ),
        MelodyAnchorSpec(
            id = PANEL_DEVICE_CONTROL_WIDGET,
            host = MelodyAnchorHost.Melody,
            feature = "panel.anc_label",
            baselineClasses = listOf("$DEVICE_CONTROL_PKG.DeviceControlWidget"),
            packages = listOf(DEVICE_CONTROL_PKG),
            query = MelodyAnchorQuery.SuperType(of(AppWidgetProvider::class.java)),
        ),
        MelodyAnchorSpec(
            id = PANEL_PREFERENCE_CATEGORY,
            host = MelodyAnchorHost.Melody,
            feature = "panel.category",
            baselineClasses = listOf(
                "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory",
                "com.coui.appcompat.preference.COUIPreferenceCategory",
            ),
            packages = listOf("com.oplus.melody.common.widget", "com.coui.appcompat.preference"),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = PANEL_COUI_MENU_PREFERENCE,
            host = MelodyAnchorHost.Melody,
            feature = "panel.menu_preference",
            baselineClasses = listOf("com.coui.appcompat.preference.COUIMenuPreference"),
            packages = listOf("com.coui.appcompat.preference"),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = BTSDK_DEVICE_INFO_MANAGER,
            host = MelodyAnchorHost.Melody,
            feature = "deviceinfo.manager",
            baselineClasses = listOf("$BTSDK_MANAGER_PKG.DeviceInfoManager"),
            packages = listOf(BTSDK_MANAGER_PKG),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = BTSDK_DEVICE_INFO,
            host = MelodyAnchorHost.Melody,
            feature = "deviceinfo.data",
            baselineClasses = listOf("$BTSDK_DATA_PKG.DeviceInfo"),
            packages = listOf(BTSDK_DATA_PKG),
            query = MelodyAnchorQuery.MethodName("getDeviceAddress", 0),
        ),
        MelodyAnchorSpec(
            id = BTSDK_NOISE_INFO,
            host = MelodyAnchorHost.Melody,
            feature = "anc.noise_info",
            baselineClasses = listOf("$BTSDK_DATA_PKG.CurrentNoiseModeInfo"),
            packages = listOf(BTSDK_DATA_PKG),
            query = MelodyAnchorQuery.MethodName("getCurrentNoiseReductionModeIndex", 0),
        ),
        MelodyAnchorSpec(
            id = DTO_EARPHONE,
            host = MelodyAnchorHost.Melody,
            feature = "dto.earphone",
            baselineClasses = listOf("$EARPHONE_PKG.EarphoneDTO"),
            packages = listOf(EARPHONE_PKG),
            query = MelodyAnchorQuery.MethodName("getMacAddress", 0),
        ),
        MelodyAnchorSpec(
            id = PROVIDER_ALIVE,
            host = MelodyAnchorHost.Melody,
            feature = "provider.alive",
            baselineClasses = listOf("com.oplus.melody.alive.provider.MelodyAliveProvider"),
            packages = listOf("com.oplus.melody.alive.provider"),
            query = MelodyAnchorQuery.SuperType(of(ContentProvider::class.java)),
        ),
        MelodyAnchorSpec(
            id = PROVIDER_MY_DEVICE,
            host = MelodyAnchorHost.Melody,
            feature = "provider.my_device",
            baselineClasses = listOf("$MY_DEVICES_PKG.MyDeviceProvider"),
            packages = listOf(MY_DEVICES_PKG),
            query = MelodyAnchorQuery.SuperType(of(ContentProvider::class.java)),
        ),
        MelodyAnchorSpec(
            id = DTO_WHITELIST_CONFIG,
            host = MelodyAnchorHost.Melody,
            feature = "whitelist.dto",
            baselineClasses = listOf("$WHITELIST_PKG.WhitelistConfigDTO"),
            packages = listOf(WHITELIST_PKG),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = DTO_WHITELIST_CONTENT,
            host = MelodyAnchorHost.Melody,
            feature = "whitelist.content",
            baselineClasses = listOf("$WHITELIST_PKG.WhitelistContentDO"),
            packages = listOf(WHITELIST_PKG),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = WHITELIST_REPO_MAPPER,
            host = MelodyAnchorHost.Melody,
            feature = "whitelist.live_data",
            baselineClasses = listOf("D0.d"),
            packages = listOf("D0"),
            // `apply(Object)Object` alone is not distinctive (a couple of dozen synthetics declare it);
            // the merged lambda class additionally declares `accept(Object)void` and a `min` method
            // count. The hook's own runtime guard (`arg is WhitelistContentDO` + official result null)
            // decides per call, so several candidates are fine.
            query = MelodyAnchorQuery.Structural(
                methods = listOf(
                    shape("apply", listOf(of(Any::class.java)), of(Any::class.java)),
                    shape("accept", listOf(of(Any::class.java)), of(Void.TYPE)),
                ),
                methodCountMin = 5,
            ),
            allowMultiple = true,
        ),
        MelodyAnchorSpec(
            id = WHITELIST_REPO_IMPL,
            host = MelodyAnchorHost.Melody,
            feature = "whitelist.repo",
            baselineClasses = listOf("com.oplus.melody.model.repository.whitelist.a", "c9.b"),
            packages = listOf("com.oplus.melody.model.repository.whitelist", "c9"),
            query = MelodyAnchorQuery.MethodName("a", 1),
            allowMultiple = true,
        ),
        /**
         * `com.oplus.melody.common.util.T` (`WhitelistUtils`): the host's *collection* lookup
         * (`a(Collection, productId, name)` / `b(BluetoothDevice, Collection)`) that the device-centre
         * card rebuild falls back to when the repository answers nothing. It never asks the repository,
         * which is where the `findWhitelistConfig failed … 404` line in the card debug came from.
         *
         * The class name survives (`com.oplus.melody.common.util` is kept), so the baseline is the
         * normal path; the `(Collection, String, String) -> WhitelistConfigDTO` shape is the fallback
         * for a release that renames `T`.
         */
        MelodyAnchorSpec(
            id = WHITELIST_UTILS,
            host = MelodyAnchorHost.Melody,
            feature = "whitelist.lookup",
            baselineClasses = listOf("$WHITELIST_UTIL_PKG.T"),
            packages = listOf(WHITELIST_UTIL_PKG),
            query = MelodyAnchorQuery.MethodSignature(
                "a",
                listOf(of(Collection::class.java), of(String::class.java), of(String::class.java)),
                anchor(DTO_WHITELIST_CONFIG),
            ),
        ),
        MelodyAnchorSpec(
            id = CARD_SENDER,
            host = MelodyAnchorHost.Melody,
            feature = "card.push",
            baselineClasses = listOf("n7.e\$a"),
            packages = listOf("n7"),
            // `sendValue(CardValue)`: the parameter type is the already-resolved card VO.
            query = MelodyAnchorQuery.MethodSignature(null, listOf(anchor(CARD_VO)), of(Void.TYPE)),
        ),
        MelodyAnchorSpec(
            id = CARD_VO,
            host = MelodyAnchorHost.Melody,
            feature = "card.push.vo",
            baselineClasses = listOf("n7.f"),
            packages = listOf("n7"),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = CARD_MENU_BUILDER,
            host = MelodyAnchorHost.Melody,
            feature = "anc.card_menu",
            baselineClasses = listOf("i9.c"),
            packages = listOf("i9"),
            // `e(MelodyApplication, String, sdk.DeviceInfo, CurrentNoiseModeInfo, List, int)`: every
            // parameter is a stable named class, so the shape survives both a class and a method rename.
            query = MelodyAnchorQuery.MethodSignature(
                null,
                listOf(
                    named("com.oplus.melody.MelodyApplication"),
                    of(String::class.java),
                    named("com.oplus.mydevices.sdk.device.DeviceInfo"),
                    anchor(BTSDK_NOISE_INFO),
                    of(List::class.java),
                    of(Int::class.javaPrimitiveType!!),
                ),
                of(Void.TYPE),
            ),
        ),
        MelodyAnchorSpec(
            id = CARD_MENU_SDK_MANAGER,
            host = MelodyAnchorHost.Melody,
            feature = "anc.card_menu.sdk",
            baselineClasses = listOf("com.oplus.mydevices.sdk.DeviceInfoManager"),
            packages = listOf("com.oplus.mydevices.sdk"),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = CARD_WIDGET_PROVIDER,
            host = MelodyAnchorHost.Melody,
            feature = "card.widget",
            baselineClasses = listOf("com.oplus.melody.card.MelodyCardWidgetProvider"),
            packages = listOf("com.oplus.melody.card"),
            query = MelodyAnchorQuery.SuperType(of(AppWidgetProvider::class.java)),
        ),
        MelodyAnchorSpec(
            id = ANC_REFRESH_ITEM,
            host = MelodyAnchorHost.Melody,
            feature = "anc.refresh.item",
            baselineClasses = listOf("$NOISE_ITEM_PKG.NoiseReductionItem"),
            packages = listOf(NOISE_ITEM_PKG),
            query = MelodyAnchorQuery.MethodName("onEarphoneDataChanged", 1),
        ),
        MelodyAnchorSpec(
            id = ANC_REFRESH_ONE_SPACE,
            host = MelodyAnchorHost.Melody,
            feature = "anc.refresh.onespace",
            baselineClasses = listOf("com.oplus.melody.onespace.b"),
            packages = listOf("com.oplus.melody.onespace"),
            query = MelodyAnchorQuery.MethodName("onViewCreated", 2),
        ),
        MelodyAnchorSpec(
            id = ANC_REFRESH_VO,
            host = MelodyAnchorHost.Melody,
            feature = "anc.refresh.vo",
            baselineClasses = listOf("Ba.z"),
            packages = listOf("Ba"),
            // `NoiseReductionVO`: `getNoiseReductionUIVersion` is declared by three classes, but
            // `supportStrongNoiseReductionRealTime` only by this one.
            query = MelodyAnchorQuery.MethodName("supportStrongNoiseReductionRealTime", 0),
        ),
        MelodyAnchorSpec(
            id = EARPHONE_REPOSITORY,
            host = MelodyAnchorHost.Melody,
            feature = "repo.earphone.base",
            baselineClasses = listOf("$EARPHONE_PKG.b"),
            packages = listOf(EARPHONE_PKG),
            query = MelodyAnchorQuery.BaselineOnly,
        ),
        MelodyAnchorSpec(
            id = SETTINGS_PODS_DATA_MANAGER,
            host = MelodyAnchorHost.Settings,
            feature = "settings.pods",
            baselineClasses = listOf("E2.b"),
            packages = listOf("E2"),
            query = MelodyAnchorQuery.MethodName("d", 1),
        ),
    )

    private val byId: Map<String, MelodyAnchorSpec> = all.associateBy { it.id }

    fun spec(id: String): MelodyAnchorSpec? = byId[id]

    /** Every anchor of one host package, in catalog order. */
    fun forHost(host: MelodyAnchorHost): List<MelodyAnchorSpec> = all.filter { it.host == host }

    /**
     * Human label for an anchor id, used by the update prompt so a miss reads as a feature
     * ("设备卡片菜单") instead of a symbol. Falls back to the id.
     */
    fun labelOf(id: String): String = LABELS[id] ?: id

    private val LABELS: Map<String, String> = mapOf(
        TRANSPORT_CLIENT to "传输抑制·客户端绑定",
        TRANSPORT_WRITE to "传输抑制·写入拦截",
        REDIRECT_V0 to "官方 ANC 写入收口",
        REDIRECT_V0_RESULT to "ANC 写入返回值",
        REDIRECT_SETGATE to "setgate 广播接管",
        REDIRECT_PROVIDER to "设备中心 SDK 接管",
        PANEL_GROUP_OBSERVER to "详情页分组模型",
        PANEL_DEVICE_CONTROL_WIDGET to "ANC 模式文案",
        PANEL_PREFERENCE_CATEGORY to "面板分组行容器",
        PANEL_COUI_MENU_PREFERENCE to "面板选项行",
        BTSDK_DEVICE_INFO_MANAGER to "设备注册表",
        BTSDK_DEVICE_INFO to "设备信息对象",
        BTSDK_NOISE_INFO to "设备卡片降噪状态",
        DTO_EARPHONE to "详情页头部投影",
        PROVIDER_ALIVE to "白名单查询通道",
        PROVIDER_MY_DEVICE to "设备中心卡片通道",
        DTO_WHITELIST_CONFIG to "白名单配置对象",
        DTO_WHITELIST_CONTENT to "白名单内容对象",
        WHITELIST_REPO_MAPPER to "详情页能力表数据源",
        WHITELIST_REPO_IMPL to "白名单仓库注入",
        WHITELIST_UTILS to "白名单集合查找兜底",
        CARD_SENDER to "桌面卡片数据推送",
        CARD_VO to "桌面卡片数据对象",
        CARD_MENU_BUILDER to "设备卡片菜单构建",
        CARD_MENU_SDK_MANAGER to "设备卡片 SDK",
        CARD_WIDGET_PROVIDER to "桌面卡片入口",
        ANC_REFRESH_ITEM to "ANC 状态重刷·详情页",
        ANC_REFRESH_ONE_SPACE to "ANC 状态重刷·OneSpace",
        ANC_REFRESH_VO to "ANC 状态重刷·VO",
        EARPHONE_REPOSITORY to "耳机仓库基类",
        SETTINGS_PODS_DATA_MANAGER to "蓝牙设置页“耳机功能”入口",
    )

    /** The nearest host frame helper used by the redirect logs; kept here so ids and prefixes stay together. */
    const val HOST_PACKAGE_PREFIX: String = "com.oplus."
}
