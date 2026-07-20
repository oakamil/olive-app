package com.oakamil.olive

import cedar.CedarOuterClass.ActionRequest
import cedar.CedarOuterClass.EmptyMessage
import cedar.CedarOuterClass.FrameRequest
import cedar.CedarOuterClass.FrameResult
import cedar.CedarOuterClass.OperationSettings
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.MethodDescriptor
import io.grpc.protobuf.lite.ProtoLiteUtils
import io.grpc.stub.ClientCalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CedarClient(val host: String, val port: Int) {
    private var channel: ManagedChannel? = null

    fun connect() {
        if (channel == null || channel!!.isShutdown) {
            channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()
        }
    }

    fun shutdown() {
        channel?.shutdown()
        channel = null
    }

    suspend fun getFrame(request: FrameRequest): FrameResult = withContext(Dispatchers.IO) {
        val method = MethodDescriptor.newBuilder<FrameRequest, FrameResult>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName("cedar.Cedar", "GetFrame"))
            .setRequestMarshaller(ProtoLiteUtils.marshaller(FrameRequest.getDefaultInstance()))
            .setResponseMarshaller(ProtoLiteUtils.marshaller(FrameResult.getDefaultInstance()))
            .build()
        ClientCalls.blockingUnaryCall(channel, method, io.grpc.CallOptions.DEFAULT, request)
    }

    suspend fun updateOperationSettings(request: OperationSettings): OperationSettings =
        withContext(Dispatchers.IO) {
            val method = MethodDescriptor.newBuilder<OperationSettings, OperationSettings>()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(
                    MethodDescriptor.generateFullMethodName(
                        "cedar.Cedar",
                        "UpdateOperationSettings"
                    )
                )
                .setRequestMarshaller(ProtoLiteUtils.marshaller(OperationSettings.getDefaultInstance()))
                .setResponseMarshaller(ProtoLiteUtils.marshaller(OperationSettings.getDefaultInstance()))
                .build()
            ClientCalls.blockingUnaryCall(channel, method, io.grpc.CallOptions.DEFAULT, request)
        }

    suspend fun initiateAction(request: ActionRequest): EmptyMessage = withContext(Dispatchers.IO) {
        val method = MethodDescriptor.newBuilder<ActionRequest, EmptyMessage>()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(
                MethodDescriptor.generateFullMethodName(
                    "cedar.Cedar",
                    "InitiateAction"
                )
            )
            .setRequestMarshaller(ProtoLiteUtils.marshaller(ActionRequest.getDefaultInstance()))
            .setResponseMarshaller(ProtoLiteUtils.marshaller(EmptyMessage.getDefaultInstance()))
            .build()
        ClientCalls.blockingUnaryCall(channel, method, io.grpc.CallOptions.DEFAULT, request)
    }
}
