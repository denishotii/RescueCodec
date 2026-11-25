// SPDX-License-Identifier: Apache-2.0
package org.rescuecodec.codec

/**
 * A message or packet was rejected.
 *
 * @property code stable machine-readable code from spec section 5: `empty`, `too_large`,
 *   `unsupported_version`, `malformed`, `no_body`, `multiple_bodies`, `unknown_key`,
 *   `invalid_value`, `missing_field`.
 * @property field path of the offending field such as `pos.lat`, or null.
 */
class CodecException(val code: String, message: String, val field: String? = null) :
    IllegalArgumentException("$code: $message" + (if (field != null) " ($field)" else ""))
