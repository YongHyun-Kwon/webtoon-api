package com.assigment.webtoonapi.exception

class WebtoonException(val errorCode: ErrorCode) : RuntimeException(errorCode.message)
