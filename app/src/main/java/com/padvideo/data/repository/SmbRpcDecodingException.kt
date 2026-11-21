package com.padvideo.data.repository

import java.io.IOException

// Android replacement for the RPC library's java.rmi.UnmarshalException.
internal class SmbRpcDecodingException : IOException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Exception) : super(message, cause)
}
