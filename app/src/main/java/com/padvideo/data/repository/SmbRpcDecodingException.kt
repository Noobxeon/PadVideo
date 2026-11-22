package com.padvideo.data.repository

import java.io.IOException

// Android replacement for com.rapid7.client.dcerpc's java.rmi.UnmarshalException.
internal class SmbRpcDecodingException : IOException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Exception) : super(message, cause)
}
