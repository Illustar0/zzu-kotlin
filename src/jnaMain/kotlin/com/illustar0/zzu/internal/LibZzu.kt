package com.illustar0.zzu.internal

import com.sun.jna.Callback
import com.sun.jna.IntegerType
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference

/** C `size_t`；JNA 经反射构造，必须保留无参构造函数。 */
internal class SizeT @JvmOverloads constructor(value: Long = 0) :
    IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toByte(): Byte = toInt().toByte()

    override fun toShort(): Short = toInt().toShort()
}

/** C `ZzuCallback`；运行在 Rust 工作线程上，异常不得穿出。 */
internal fun interface NativeCallback : Callback {
    fun invoke(userData: Pointer, status: Int, payload: Pointer)
}

/** 各种客户端共用的 `zzu_*_call` 签名。 */
internal typealias NativeCallFunction = (
    client: Pointer,
    method: String,
    params: ByteArray?,
    paramsLength: SizeT,
    callback: NativeCallback,
    userData: Pointer,
    outCall: PointerByReference,
) -> Int

/** `libzzu.h` 的 JNA 直接映射，函数名即 C 符号名。 */
// JNA 直接映射须保留 C ABI 的函数名、签名与统一注册。
@Suppress("FunctionName", "LongParameterList", "TooManyFunctions")
internal object LibZzu {
    init {
        val library = NativeLibrary.getInstance("zzu")
        checkAbiVersion(library)
        Native.register(LibZzu::class.java, library)
    }

    /** 绑定前校验：版本不符的库可能缺符号，那样只会得到一条看不出原因的链接错误。 */
    private fun checkAbiVersion(library: NativeLibrary) {
        val version = library.getFunction("zzu_abi_version").invokeInt(emptyArray())
        verifyAbiVersion(version) {
            library.getFunction("zzu_version").invokeString(emptyArray(), false)
        }
    }

    @JvmStatic
    external fun zzu_buffer_data(buffer: Pointer): Pointer

    @JvmStatic
    external fun zzu_buffer_len(buffer: Pointer): SizeT

    @JvmStatic
    external fun zzu_buffer_free(buffer: Pointer)

    @JvmStatic
    external fun zzu_call_cancel(call: Pointer)

    @JvmStatic
    external fun zzu_call_free(call: Pointer)

    @JvmStatic
    external fun zzu_runtime_new(
        options: ByteArray?,
        optionsLength: SizeT,
        outRuntime: PointerByReference,
        outError: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_cas_new(
        runtime: Pointer,
        config: ByteArray,
        configLength: SizeT,
        outCas: PointerByReference,
        outError: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_cas_free(cas: Pointer)

    @JvmStatic
    external fun zzu_cas_call(
        cas: Pointer,
        method: String,
        params: ByteArray?,
        paramsLength: SizeT,
        callback: NativeCallback,
        userData: Pointer,
        outCall: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_eas_new(
        cas: Pointer,
        outEas: PointerByReference,
        outError: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_eas_free(eas: Pointer)

    @JvmStatic
    external fun zzu_eas_call(
        eas: Pointer,
        method: String,
        params: ByteArray?,
        paramsLength: SizeT,
        callback: NativeCallback,
        userData: Pointer,
        outCall: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_ecard_new(
        cas: Pointer,
        outEcard: PointerByReference,
        outError: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_ecard_free(ecard: Pointer)

    @JvmStatic
    external fun zzu_ecard_call(
        ecard: Pointer,
        method: String,
        params: ByteArray?,
        paramsLength: SizeT,
        callback: NativeCallback,
        userData: Pointer,
        outCall: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_mail_new(
        cas: Pointer,
        outMail: PointerByReference,
        outError: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_mail_free(mail: Pointer)

    @JvmStatic
    external fun zzu_mail_call(
        mail: Pointer,
        method: String,
        params: ByteArray?,
        paramsLength: SizeT,
        callback: NativeCallback,
        userData: Pointer,
        outCall: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_portal_discover(
        runtime: Pointer,
        options: ByteArray,
        optionsLength: SizeT,
        callback: NativeCallback,
        userData: Pointer,
        outCall: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_portal_new(
        runtime: Pointer,
        config: ByteArray,
        configLength: SizeT,
        outPortal: PointerByReference,
        outError: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_portal_free(portal: Pointer)

    @JvmStatic
    external fun zzu_portal_call(
        portal: Pointer,
        method: String,
        params: ByteArray?,
        paramsLength: SizeT,
        callback: NativeCallback,
        userData: Pointer,
        outCall: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_self_service_new(
        runtime: Pointer,
        config: ByteArray,
        configLength: SizeT,
        outService: PointerByReference,
        outError: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_self_service_free(service: Pointer)

    @JvmStatic
    external fun zzu_self_service_call(
        service: Pointer,
        method: String,
        params: ByteArray?,
        paramsLength: SizeT,
        callback: NativeCallback,
        userData: Pointer,
        outCall: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_timetable_to_calendar(
        params: ByteArray,
        paramsLength: SizeT,
        outPayload: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_room_id_parse(
        room: ByteArray,
        roomLength: SizeT,
        outPayload: PointerByReference,
    ): Int

    @JvmStatic
    external fun zzu_recharge_order_checkout_url_for(
        params: ByteArray,
        paramsLength: SizeT,
        outPayload: PointerByReference,
    ): Int
}
