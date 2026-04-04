package ru.krypto.gateway.services

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Converts internal integer representations to decimal strings and back.
 *
 * Orderbook prices/quantities use a fixed 10^8 multiplier (DEFAULT_SCALE).
 * Account balances use asset-specific decimals (e.g. BTC=8, USDT=6, ETH=18)
 * and must be converted via [balanceToDecimalString].
 */
object PrecisionService {
    private const val DEFAULT_SCALE = 8
    private val DIVISOR = BigDecimal.TEN.pow(DEFAULT_SCALE)

    // ── Orderbook price/quantity conversions (fixed 10^8) ──

    fun longToDecimalString(value: Long, scale: Int = DEFAULT_SCALE): String {
        return BigDecimal(value).divide(DIVISOR, scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun bigIntToDecimalString(value: BigInteger, scale: Int = DEFAULT_SCALE): String {
        return BigDecimal(value).divide(DIVISOR, scale, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    fun decimalStringToLong(value: String): Long {
        return BigDecimal(value).multiply(DIVISOR).toLong()
    }

    fun decimalStringToBigInteger(value: String): BigInteger {
        return BigDecimal(value).multiply(DIVISOR).toBigInteger()
    }

    // ── Balance conversions (asset-specific decimals) ──

    /**
     * Convert a raw balance value (BigInteger string from account-service) to a human-readable
     * decimal string using the asset's native decimal precision.
     *
     * Example: "1000000" with assetDecimals=6 → "1" (1 USDT)
     *          "1000000000" with assetDecimals=8 → "10" (10 BTC)
     */
    fun balanceToDecimalString(rawValue: String, assetDecimals: Int): String {
        val bigInt = rawValue.toBigIntegerOrNull() ?: BigInteger.ZERO
        val divisor = BigDecimal.TEN.pow(assetDecimals)
        return BigDecimal(bigInt).divide(divisor, assetDecimals, RoundingMode.HALF_UP)
            .stripTrailingZeros().toPlainString()
    }
}
