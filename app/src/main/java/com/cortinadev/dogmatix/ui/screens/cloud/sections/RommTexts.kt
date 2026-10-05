package com.cortinadev.dogmatix.ui.screens.cloud.sections

import androidx.annotation.StringRes
import com.cortinadev.dogmatix.R
import com.cortinadev.dogmatix.data.service.RommErrorKind

/**
 * The user-facing line for a [RommErrorKind] (5.0). Shared by the RomM pieces and usable by the
 * Cloud hub: `stringResource(rommErrorText(info.errorKind))`.
 */
@StringRes
fun rommErrorText(kind: RommErrorKind?): Int = when (kind) {
    RommErrorKind.NOT_SET_UP -> R.string.romm5_err_not_set_up
    RommErrorKind.UNREACHABLE -> R.string.romm5_err_unreachable
    RommErrorKind.TLS -> R.string.romm5_err_tls
    RommErrorKind.AUTH -> R.string.romm5_err_auth
    RommErrorKind.FORBIDDEN -> R.string.romm5_err_forbidden
    RommErrorKind.NOT_FOUND -> R.string.romm5_err_not_found
    RommErrorKind.SERVER, null -> R.string.romm5_err_server
}
