package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 唤醒 broker 回归(上游 99ba119 现场报告 R1-5X8J5PQVR8TRWBN0MS06X59FDW 的等价场景):
 * broker 只警告一次后便再未捕获宿主,keepalive 无法在息屏时重新点亮 AOD,而 wake-broker
 * capability 仍能从符号解析成功。同一片沉默有两个成因——插件实例早于构造器 hook(预加载
 * 或复用的 AOD 插件),以及一次没说出口的安装退场;下面这组纯函数把它们分开。
 */
class AodWakeBrokerRecoveryTest {
    @Test
    fun aLiveHostAdoptedFromAnExistingHookSatisfiesEveryReference() {
        // 构造器接缝曾是唯一的捕获路径。收编(adopt)才让「hook 之前创建的插件实例」可用。
        assertEquals(
            AodWakeAvailability.READY,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = true,
                powerManagerResolved = true,
                interactive = false
            )
        )
    }

    @Test
    fun everyMissingReferenceNamesItself() {
        // 单一 "unavailable" 字符串让现场白跑一个来回:日志说不出缺的是哪个引用。
        assertEquals(
            AodWakeAvailability.NO_HOST,
            resolveAodWakeAvailability(
                hostCaptured = false,
                methodResolved = true,
                powerManagerResolved = true,
                interactive = false
            )
        )
        assertEquals(
            AodWakeAvailability.NO_METHOD,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = false,
                powerManagerResolved = true,
                interactive = false
            )
        )
        assertEquals(
            AodWakeAvailability.NO_POWER_MANAGER,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = true,
                powerManagerResolved = false,
                interactive = false
            )
        )
    }

    @Test
    fun aMissingReferenceOutranksAnInteractiveScreenSoTheFaultIsNotHidden() {
        // 交互性是常规抑制;把它当故障上报等于把健康的抑制描述成缺陷,反过来则把缺陷藏起来。
        assertEquals(
            AodWakeAvailability.NO_HOST,
            resolveAodWakeAvailability(
                hostCaptured = false,
                methodResolved = false,
                powerManagerResolved = false,
                interactive = true
            )
        )
        assertEquals(
            AodWakeAvailability.INTERACTIVE,
            resolveAodWakeAvailability(
                hostCaptured = true,
                methodResolved = true,
                powerManagerResolved = true,
                interactive = true
            )
        )
        assertTrue(AodWakeAvailability.INTERACTIVE.isFault.not())
        assertTrue(AodWakeAvailability.READY.isFault.not())
    }

    @Test
    fun adoptionReplacesOnlyWithADifferentLiveInstance() {
        val host = Any()
        val recreated = Any()
        assertTrue(shouldAdoptAodWakeReference(current = null, candidate = host))
        assertTrue(shouldAdoptAodWakeReference(current = Any(), candidate = host))
        // 系统拆掉插件再建一个宿主正是本接缝存在的理由:不同的活实例替换旧引用。
        assertTrue(shouldAdoptAodWakeReference(current = host, candidate = recreated))
        // 同一实例重复移交不得重复记日志;null 永远不清掉系统仍在使用的引用。
        assertFalse(shouldAdoptAodWakeReference(current = host, candidate = host))
        assertFalse(shouldAdoptAodWakeReference(current = host, candidate = null))
    }

    @Test
    fun aDistinctLaterFaultIsStillReported() {
        // 被替代的单一全局闩锁只报第一个故障:先丢宿主再丢电源管理器与从来就没有宿主
        // 在日志里一模一样。
        assertTrue(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.NO_HOST,
                lastReported = null
            )
        )
        assertFalse(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.NO_HOST,
                lastReported = AodWakeAvailability.NO_HOST
            )
        )
        assertTrue(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.NO_POWER_MANAGER,
                lastReported = AodWakeAvailability.NO_HOST
            )
        )
    }

    @Test
    fun aSuppressedInteractiveRequestIsNeverLoggedAsAFault() {
        assertFalse(
            shouldReportAodWakeUnavailable(
                AodWakeAvailability.INTERACTIVE,
                lastReported = null
            )
        )
        assertFalse(
            shouldReportAodWakeUnavailable(AodWakeAvailability.READY, lastReported = null)
        )
    }

    @Test
    fun aHostRefusalCarriesTheRecordedInstallReasons() {
        // 「no host」单独一条说不清宿主是「从未移交」还是「安装器从未绑定」,
        // 而这正是现场报告必须回答的问题。
        assertTrue(
            aodWakeUnavailableDetail(AodWakeAvailability.NO_HOST, installSkips = "triggers_class")
                .contains("install=triggers_class")
        )
        assertTrue(
            aodWakeUnavailableDetail(AodWakeAvailability.NO_HOST, installSkips = "")
                .contains("install=none")
        )
        // 非缺宿主的故障没有安装故事可讲。
        assertEquals(
            "",
            aodWakeUnavailableDetail(AodWakeAvailability.NO_METHOD, installSkips = "triggers_class")
        )
    }
}
