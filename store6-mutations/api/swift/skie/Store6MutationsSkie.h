#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class SMS__SkieTypeExportsKt, SMS__SkieSuspendWrappersKt, SMSUShort, SMSULong, SMSUInt, SMSUByte, SMSStore6_coreStoreResultRevalidated, SMSStore6_coreStoreResultLoading, SMSStore6_coreStoreResultError, SMSStore6_coreStoreResultData<V>, SMSStore6_coreStoreNamespace, SMSStore6_coreStoreErrorPersistence, SMSStore6_coreStoreErrorMissing, SMSStore6_coreStoreErrorFreshnessUnsatisfiable, SMSStore6_coreStoreErrorFetch, SMSStore6_coreStoreErrorConversion, SMSStore6_coreStoreErrorConflict, SMSStore6_coreStoreError, SMSStore6_coreOrigin, SMSStore6_coreKeyStatus, SMSStore6_coreKeyEvents, SMSStore6_coreFreshnessStaleIfError, SMSStore6_coreFreshnessMustBeFresh, SMSStore6_coreFreshnessMaxAge, SMSStore6_coreFreshnessLocalOnly, SMSStore6_coreFreshnessContext, SMSStore6_coreFreshnessCachedOrFetch, SMSStore6_coreFetcherResultSuccess<V>, SMSStore6_coreFetcherResultNotModified, SMSStore6_coreFetcherResultError, SMSStore6_coreFetcherResultDeleted, SMSStore6_coreFetchPlanSkip, SMSStore6_coreFetchPlanFetch, SMSStore6_coreFetchPlanConditional, SMSStaleSet<K>, SMSSkie_SuspendResultSuccess, SMSSkie_SuspendResultError, SMSSkie_SuspendResultCanceled, SMSSkie_SuspendResult, SMSSkie_SuspendHandler, SMSSkie_CancellationHandler, SMSSkieKotlinStateFlow<T>, SMSSkieKotlinSharedFlow<T>, SMSSkieKotlinOptionalStateFlow<T>, SMSSkieKotlinOptionalSharedFlow<T>, SMSSkieKotlinOptionalMutableStateFlow<T>, SMSSkieKotlinOptionalMutableSharedFlow<T>, SMSSkieKotlinOptionalFlow<T>, SMSSkieKotlinMutableStateFlow<T>, SMSSkieKotlinMutableSharedFlow<T>, SMSSkieKotlinFlow<T>, SMSSkieColdFlowIterator<E>, SMSShort, SMSPoisonedIntent, SMSPendingIntent, SMSNumber, SMSMutatorRegistryKt, SMSMutatorRegistryBuilder<K, V>, SMSMutatorRegistry<K, V>, SMSMutatorRef<K, V, A>, SMSMutationTombstoneState, SMSMutationStoreKt, SMSMutationStoreBuilder<K, V>, SMSMutationStore<K, V>, SMSMutationRetirementAck, SMSMutationRetirement, SMSMutationRetired, SMSMutationPush<K, V>, SMSMutationPresentAck<K, V>, SMSMutationPresenceState, SMSMutationPresencePresent<V>, SMSMutationPresenceAbsent, SMSMutationPreconditionCandidate<K, V>, SMSMutationPendingState, SMSMutationParked, SMSMutationKeyTombstoneRecord, SMSMutationKeyIdentity, SMSMutationKeyAliasRecord, SMSMutationIntentRecord, SMSMutationFailureRecord, SMSMutationFailureKind, SMSMutationFailure, SMSMutationFailed, SMSMutationExecutionRecord, SMSMutationExecutionPhase, SMSMutationEnqueued, SMSMutationEffectSkipped, SMSMutationEffectRecord, SMSMutationEffectKind, SMSMutationEffectDisposition, SMSMutationEffectApplied, SMSMutationConflictResolutionServerWins, SMSMutationConflictResolutionRetry<V>, SMSMutationConflictObserved, SMSMutationConflictBuilder<K, V>, SMSMutationClientRecord, SMSMutationCheckpointFailed, SMSMutationCheckpointConfirmed, SMSMutationAttempted, SMSMutationAttemptRecord, SMSMutationAliasState, SMSMutationAdopted, SMSMutationAcknowledged, SMSMutationAckRecord, SMSMutationAbsentAck<K, V>, SMSMutableSet<ObjectType>, SMSMutableDictionary<KeyType, ObjectType>, SMSLong, SMSKotlinUnit, SMSKotlinThrowable, SMSKotlinRuntimeException, SMSKotlinIllegalStateException, SMSKotlinException, SMSKotlinEnumCompanion, SMSKotlinEnum<E>, SMSKotlinCancellationException, SMSKotlinByteIterator, SMSKotlinByteArray, SMSKotlinArray<T>, SMSInt, SMSInMemoryMutationJournalStorage, SMSFloat, SMSDouble, SMSDeadLetter, SMSByte, SMSBoolean, SMSBase, NSString, NSSet<ObjectType>, NSObject, NSNumber, NSMutableSet<ObjectType>, NSMutableDictionary<KeyType, ObjectType>, NSMutableArray<ObjectType>, NSError, NSDictionary<KeyType, ObjectType>, NSArray<ObjectType>;

@protocol SMSStore6_coreWallClock, SMSStore6_coreStoreTelemetry, SMSStore6_coreStoreResult, SMSStore6_coreStoreMeta, SMSStore6_coreStoreKey, SMSStore6_coreStore, SMSStore6_coreSourceOfTruth, SMSStore6_coreFreshnessValidator, SMSStore6_coreFreshness, SMSStore6_coreFetcherResult, SMSStore6_coreFetcher, SMSStore6_coreFetchPlan, SMSStore6_coreBookkeeper, SMSSkie_DispatcherDelegate, SMSMutationServer, SMSMutationPresence, SMSMutationKeyResolver, SMSMutationJournalTransaction, SMSMutationJournalStorage, SMSMutationIntentEvent, SMSMutationEvent, SMSMutationConflictResolution, SMSMutationCodec, SMSMutationAck, SMSKotlinx_coroutines_coreStateFlow, SMSKotlinx_coroutines_coreSharedFlow, SMSKotlinx_coroutines_coreRunnable, SMSKotlinx_coroutines_coreMutableStateFlow, SMSKotlinx_coroutines_coreMutableSharedFlow, SMSKotlinx_coroutines_coreFlowCollector, SMSKotlinx_coroutines_coreFlow, SMSKotlinSuspendFunction1, SMSKotlinIterator, SMSKotlinFunction, SMSKotlinComparable, NSCopying;

// Due to an Obj-C/Swift interop limitation, SKIE cannot generate Swift types with a lambda type argument.
// Example of such type is: A<() -> Unit> where A<T> is a generic class.
// To avoid compilation errors SKIE replaces these type arguments with __SkieLambdaErrorType, resulting in A<__SkieLambdaErrorType>.
// Generated declarations that reference __SkieLambdaErrorType cannot be called in any way and the __SkieLambdaErrorType class cannot be used.
// The original declarations can still be used in the same way as other declarations hidden by SKIE (and with the same limitations as without SKIE).
@interface __SkieLambdaErrorType : NSObject
- (instancetype _Nonnull)init __attribute__((unavailable));
+ (instancetype _Nonnull)new __attribute__((unavailable));
@end

// Due to an Obj-C/Swift interop limitation, SKIE cannot generate Swift code that uses external Obj-C types for which SKIE doesn't know a fully qualified name.
// This problem occurs when custom Cinterop bindings are used because those do not contain the name of the Framework that provides implementation for those binding.
// The name can be configured manually using the SKIE Gradle configuration key 'ClassInterop.CInteropFrameworkName' in the same way as other SKIE features.
// To avoid compilation errors SKIE replaces types with unknown Framework name with __SkieUnknownCInteropFrameworkErrorType.
// Generated declarations that reference __SkieUnknownCInteropFrameworkErrorType cannot be called in any way and the __SkieUnknownCInteropFrameworkErrorType class cannot be used.
@interface __SkieUnknownCInteropFrameworkErrorType : NSObject
- (instancetype _Nonnull)init __attribute__((unavailable));
+ (instancetype _Nonnull)new __attribute__((unavailable));
@end

typedef id _Nonnull Skie__TypeDef__0__id __attribute__((__swift_private__));
typedef id<SMSMutationCodec> _Nonnull Skie__TypeDef__1__id_SMSMutationCodec_ __attribute__((__swift_private__));
typedef id<SMSMutationPresence> _Nonnull Skie__TypeDef__2__id_SMSMutationPresence_ __attribute__((__swift_private__));
typedef id<SMSMutationPresence> _Nullable Skie__TypeDef__3__id_SMSMutationPresence___Nullable __attribute__((__swift_private__));

NS_ASSUME_NONNULL_BEGIN
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunknown-warning-option"
#pragma clang diagnostic ignored "-Wincompatible-property-type"
#pragma clang diagnostic ignored "-Wnullability"

#pragma push_macro("_Nullable_result")
#if !__has_feature(nullability_nullable_result)
#undef _Nullable_result
#define _Nullable_result _Nullable
#endif

__attribute__((swift_name("KotlinBase")))
@interface SMSBase : NSObject
- (instancetype)init __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
+ (void)initialize __attribute__((objc_requires_super));
@end

@interface SMSBase (SMSBaseCopying) <NSCopying>
@end

__attribute__((swift_name("KotlinMutableSet")))
@interface SMSMutableSet<ObjectType> : NSMutableSet<ObjectType>
@end

__attribute__((swift_name("KotlinMutableDictionary")))
@interface SMSMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType>
@end

@interface NSError (NSErrorSMSKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

__attribute__((swift_name("KotlinNumber")))
@interface SMSNumber : NSNumber
- (instancetype)initWithChar:(char)value __attribute__((unavailable));
- (instancetype)initWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
- (instancetype)initWithShort:(short)value __attribute__((unavailable));
- (instancetype)initWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
- (instancetype)initWithInt:(int)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
- (instancetype)initWithLong:(long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
- (instancetype)initWithLongLong:(long long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
- (instancetype)initWithFloat:(float)value __attribute__((unavailable));
- (instancetype)initWithDouble:(double)value __attribute__((unavailable));
- (instancetype)initWithBool:(BOOL)value __attribute__((unavailable));
- (instancetype)initWithInteger:(NSInteger)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
+ (instancetype)numberWithChar:(char)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
+ (instancetype)numberWithShort:(short)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
+ (instancetype)numberWithInt:(int)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
+ (instancetype)numberWithLong:(long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
+ (instancetype)numberWithLongLong:(long long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
+ (instancetype)numberWithFloat:(float)value __attribute__((unavailable));
+ (instancetype)numberWithDouble:(double)value __attribute__((unavailable));
+ (instancetype)numberWithBool:(BOOL)value __attribute__((unavailable));
+ (instancetype)numberWithInteger:(NSInteger)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
@end

__attribute__((swift_name("KotlinByte")))
@interface SMSByte : SMSNumber
- (instancetype)initWithChar:(char)value;
+ (instancetype)numberWithChar:(char)value;
@end

__attribute__((swift_name("KotlinUByte")))
@interface SMSUByte : SMSNumber
- (instancetype)initWithUnsignedChar:(unsigned char)value;
+ (instancetype)numberWithUnsignedChar:(unsigned char)value;
@end

__attribute__((swift_name("KotlinShort")))
@interface SMSShort : SMSNumber
- (instancetype)initWithShort:(short)value;
+ (instancetype)numberWithShort:(short)value;
@end

__attribute__((swift_name("KotlinUShort")))
@interface SMSUShort : SMSNumber
- (instancetype)initWithUnsignedShort:(unsigned short)value;
+ (instancetype)numberWithUnsignedShort:(unsigned short)value;
@end

__attribute__((swift_name("KotlinInt")))
@interface SMSInt : SMSNumber
- (instancetype)initWithInt:(int)value;
+ (instancetype)numberWithInt:(int)value;
@end

__attribute__((swift_name("KotlinUInt")))
@interface SMSUInt : SMSNumber
- (instancetype)initWithUnsignedInt:(unsigned int)value;
+ (instancetype)numberWithUnsignedInt:(unsigned int)value;
@end

__attribute__((swift_name("KotlinLong")))
@interface SMSLong : SMSNumber
- (instancetype)initWithLongLong:(long long)value;
+ (instancetype)numberWithLongLong:(long long)value;
@end

__attribute__((swift_name("KotlinULong")))
@interface SMSULong : SMSNumber
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value;
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value;
@end

__attribute__((swift_name("KotlinFloat")))
@interface SMSFloat : SMSNumber
- (instancetype)initWithFloat:(float)value;
+ (instancetype)numberWithFloat:(float)value;
@end

__attribute__((swift_name("KotlinDouble")))
@interface SMSDouble : SMSNumber
- (instancetype)initWithDouble:(double)value;
+ (instancetype)numberWithDouble:(double)value;
@end

__attribute__((swift_name("KotlinBoolean")))
@interface SMSBoolean : SMSNumber
- (instancetype)initWithBool:(BOOL)value;
+ (instancetype)numberWithBool:(BOOL)value;
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieColdFlowIterator")))
@interface SMSSkieColdFlowIterator<E> : SMSBase
- (instancetype)initWithFlow:(id<SMSKotlinx_coroutines_coreFlow>)flow __attribute__((swift_name("init(flow:)"))) __attribute__((objc_designated_initializer));
- (void)cancel __attribute__((swift_name("cancel()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)hasNextWithCompletionHandler:(void (^)(SMSBoolean * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("hasNext(completionHandler:)")));
- (E _Nullable)next __attribute__((swift_name("next()")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlow")))
@protocol SMSKotlinx_coroutines_coreFlow
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinFlow")))
@interface SMSSkieKotlinFlow<__covariant T> : SMSBase <SMSKotlinx_coroutines_coreFlow>
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreSharedFlow")))
@protocol SMSKotlinx_coroutines_coreSharedFlow <SMSKotlinx_coroutines_coreFlow>
@required
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlowCollector")))
@protocol SMSKotlinx_coroutines_coreFlowCollector
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(id _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreMutableSharedFlow")))
@protocol SMSKotlinx_coroutines_coreMutableSharedFlow <SMSKotlinx_coroutines_coreSharedFlow, SMSKotlinx_coroutines_coreFlowCollector>
@required

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(id _Nullable)value __attribute__((swift_name("tryEmit(value:)")));
@property (readonly) id<SMSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinMutableSharedFlow")))
@interface SMSSkieKotlinMutableSharedFlow<T> : SMSBase <SMSKotlinx_coroutines_coreMutableSharedFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SMSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreMutableSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreStateFlow")))
@protocol SMSKotlinx_coroutines_coreStateFlow <SMSKotlinx_coroutines_coreSharedFlow>
@required
@property (readonly) id _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreMutableStateFlow")))
@protocol SMSKotlinx_coroutines_coreMutableStateFlow <SMSKotlinx_coroutines_coreStateFlow, SMSKotlinx_coroutines_coreMutableSharedFlow>
@required
- (void)setValue:(id _Nullable)value __attribute__((swift_name("setValue(_:)")));
- (BOOL)compareAndSetExpect:(id _Nullable)expect update:(id _Nullable)update __attribute__((swift_name("compareAndSet(expect:update:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinMutableStateFlow")))
@interface SMSSkieKotlinMutableStateFlow<T> : SMSBase <SMSKotlinx_coroutines_coreMutableStateFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SMSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@property T value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreMutableStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
- (BOOL)compareAndSetExpect:(T)expect update:(T)update __attribute__((swift_name("compareAndSet(expect:update:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalFlow")))
@interface SMSSkieKotlinOptionalFlow<__covariant T> : SMSBase <SMSKotlinx_coroutines_coreFlow>
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalMutableSharedFlow")))
@interface SMSSkieKotlinOptionalMutableSharedFlow<T> : SMSBase <SMSKotlinx_coroutines_coreMutableSharedFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SMSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreMutableSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T _Nullable)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalMutableStateFlow")))
@interface SMSSkieKotlinOptionalMutableStateFlow<T> : SMSBase <SMSKotlinx_coroutines_coreMutableStateFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<SMSKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@property T _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreMutableStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
- (BOOL)compareAndSetExpect:(T _Nullable)expect update:(T _Nullable)update __attribute__((swift_name("compareAndSet(expect:update:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(T _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(T _Nullable)value __attribute__((swift_name("tryEmit(value:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalSharedFlow")))
@interface SMSSkieKotlinOptionalSharedFlow<__covariant T> : SMSBase <SMSKotlinx_coroutines_coreSharedFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalStateFlow")))
@interface SMSSkieKotlinOptionalStateFlow<__covariant T> : SMSBase <SMSKotlinx_coroutines_coreStateFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) T _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinSharedFlow")))
@interface SMSSkieKotlinSharedFlow<__covariant T> : SMSBase <SMSKotlinx_coroutines_coreSharedFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinStateFlow")))
@interface SMSSkieKotlinStateFlow<__covariant T> : SMSBase <SMSKotlinx_coroutines_coreStateFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) T value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<SMSKotlinx_coroutines_coreStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_CancellationHandler")))
@interface SMSSkie_CancellationHandler : SMSBase
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (void)cancel __attribute__((swift_name("cancel()")));
@end

__attribute__((swift_name("Skie_DispatcherDelegate")))
@protocol SMSSkie_DispatcherDelegate
@required
- (void)dispatchBlock:(id<SMSKotlinx_coroutines_coreRunnable>)block __attribute__((swift_name("dispatch(block:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendHandler")))
@interface SMSSkie_SuspendHandler : SMSBase
- (instancetype)initWithCancellationHandler:(SMSSkie_CancellationHandler *)cancellationHandler dispatcherDelegate:(id<SMSSkie_DispatcherDelegate>)dispatcherDelegate onResult:(void (^)(SMSSkie_SuspendResult *))onResult __attribute__((swift_name("init(cancellationHandler:dispatcherDelegate:onResult:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Skie_SuspendResult")))
@interface SMSSkie_SuspendResult : SMSBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Canceled")))
@interface SMSSkie_SuspendResultCanceled : SMSSkie_SuspendResult
@property (class, readonly, getter=shared) SMSSkie_SuspendResultCanceled *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)canceled __attribute__((swift_name("init()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Error")))
@interface SMSSkie_SuspendResultError : SMSSkie_SuspendResult
@property (readonly) NSError *error __attribute__((swift_name("error")));
- (instancetype)initWithError:(NSError *)error __attribute__((swift_name("init(error:)"))) __attribute__((objc_designated_initializer));
- (SMSSkie_SuspendResultError *)doCopyError:(NSError *)error __attribute__((swift_name("doCopy(error:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Success")))
@interface SMSSkie_SuspendResultSuccess : SMSSkie_SuspendResult
@property (readonly) id _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(id _Nullable)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (SMSSkie_SuspendResultSuccess *)doCopyValue:(id _Nullable)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DeadLetter")))
@interface SMSDeadLetter : SMSBase
@property (readonly) int32_t attempts __attribute__((swift_name("attempts")));
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));
@property (readonly) SMSMutationFailure *failure __attribute__((swift_name("failure")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));
@property (readonly) int64_t parkedAtEpochMillis __attribute__((swift_name("parkedAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationAck")))
@protocol SMSMutationAck
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAbsentAck")))
@interface SMSMutationAbsentAck<K, V> : SMSBase <SMSMutationAck>
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
- (instancetype)initWithEtag:(NSString * _Nullable)etag __attribute__((swift_name("init(etag:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationEvent")))
@protocol SMSMutationEvent
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationIntentEvent")))
@protocol SMSMutationIntentEvent <SMSMutationEvent>
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAcknowledged")))
@interface SMSMutationAcknowledged : SMSBase <SMSMutationIntentEvent>
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@property (readonly) SMSMutationPresenceState *presence __attribute__((swift_name("presence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAdopted")))
@interface SMSMutationAdopted : SMSBase <SMSMutationIntentEvent>
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@property (readonly) SMSMutationPresenceState *presence __attribute__((swift_name("presence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAttempted")))
@interface SMSMutationAttempted : SMSBase <SMSMutationIntentEvent>
@property (readonly) int32_t attempt __attribute__((swift_name("attempt")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationCheckpointConfirmed")))
@interface SMSMutationCheckpointConfirmed : SMSBase <SMSMutationEvent>
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t confirmedThroughSequence __attribute__((swift_name("confirmedThroughSequence")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@property (readonly) int64_t requestedThroughSequence __attribute__((swift_name("requestedThroughSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationCheckpointFailed")))
@interface SMSMutationCheckpointFailed : SMSBase <SMSMutationEvent>
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) SMSMutationFailure *failure __attribute__((swift_name("failure")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@property (readonly) int64_t requestedThroughSequence __attribute__((swift_name("requestedThroughSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationCodec")))
@protocol SMSMutationCodec
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (id)decodeVersion:(int32_t)version bytes:(SMSKotlinByteArray *)bytes __attribute__((swift_name("decode(version:bytes:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (SMSKotlinByteArray *)encodeValue:(id)value __attribute__((swift_name("encode(value:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictBuilder")))
@interface SMSMutationConflictBuilder<K, V> : SMSBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)mergeMerge:(id<SMSMutationConflictResolution> (^)(id<SMSMutationPresence>, id<SMSMutationPresence>, id<SMSMutationPresence>))merge __attribute__((swift_name("merge(merge:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)preconditionSelect:(id<SMSStore6_coreStoreMeta> _Nullable (^)(SMSMutationPreconditionCandidate<K, V> *))select __attribute__((swift_name("precondition(select:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictObserved")))
@interface SMSMutationConflictObserved : SMSBase <SMSMutationIntentEvent>
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@property (readonly) id<SMSStore6_coreStoreMeta> _Nullable serverMeta __attribute__((swift_name("serverMeta")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationConflictResolution")))
@protocol SMSMutationConflictResolution
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictResolutionRetry")))
@interface SMSMutationConflictResolutionRetry<V> : SMSBase <SMSMutationConflictResolution>
@property (readonly) id<SMSMutationPresence> value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(id<SMSMutationPresence>)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationConflictResolutionServerWins")))
@interface SMSMutationConflictResolutionServerWins : SMSBase <SMSMutationConflictResolution>
@property (class, readonly, getter=shared) SMSMutationConflictResolutionServerWins *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)serverWins __attribute__((swift_name("init()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEffectApplied")))
@interface SMSMutationEffectApplied : SMSBase <SMSMutationIntentEvent>
@property (readonly) int32_t effectIndex __attribute__((swift_name("effectIndex")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEffectSkipped")))
@interface SMSMutationEffectSkipped : SMSBase <SMSMutationIntentEvent>
@property (readonly) int32_t effectIndex __attribute__((swift_name("effectIndex")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEnqueued")))
@interface SMSMutationEnqueued : SMSBase <SMSMutationIntentEvent>
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationFailed")))
@interface SMSMutationFailed : SMSBase <SMSMutationIntentEvent>
@property (readonly) SMSMutationFailure *failure __attribute__((swift_name("failure")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@property (readonly) SMSMutationPendingState *state __attribute__((swift_name("state")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationFailure")))
@interface SMSMutationFailure : SMSBase
@property (readonly) NSString *detail __attribute__((swift_name("detail")));
@property (readonly) SMSMutationFailureKind *kind __attribute__((swift_name("kind")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end

__attribute__((swift_name("KotlinComparable")))
@protocol SMSKotlinComparable
@required
- (int32_t)compareToOther:(id _Nullable)other __attribute__((swift_name("compareTo(other:)")));
@end

__attribute__((swift_name("KotlinEnum")))
@interface SMSKotlinEnum<E> : SMSBase <SMSKotlinComparable>
@property (class, readonly, getter=companion) SMSKotlinEnumCompanion *companion __attribute__((swift_name("companion")));
@property (readonly) NSString *name __attribute__((swift_name("name")));
@property (readonly) int32_t ordinal __attribute__((swift_name("ordinal")));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer));
- (int32_t)compareToOther:(E)other __attribute__((swift_name("compareTo(other:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationFailureKind")))
@interface SMSMutationFailureKind : SMSKotlinEnum<SMSMutationFailureKind *>
@property (class, readonly) SMSMutationFailureKind *identity __attribute__((swift_name("identity")));
@property (class, readonly) SMSMutationFailureKind *codec __attribute__((swift_name("codec")));
@property (class, readonly) SMSMutationFailureKind *projection __attribute__((swift_name("projection")));
@property (class, readonly) SMSMutationFailureKind *protocol __attribute__((swift_name("protocol")));
@property (class, readonly) SMSMutationFailureKind *conflict __attribute__((swift_name("conflict")));
@property (class, readonly) SMSMutationFailureKind *transport __attribute__((swift_name("transport")));
@property (class, readonly) SMSMutationFailureKind *adoption __attribute__((swift_name("adoption")));
@property (class, readonly) SMSMutationFailureKind *effect __attribute__((swift_name("effect")));
@property (class, readonly) SMSMutationFailureKind *persistence __attribute__((swift_name("persistence")));
@property (class, readonly) NSArray<SMSMutationFailureKind *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationFailureKind *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationKeyIdentity")))
@interface SMSMutationKeyIdentity : SMSBase
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationKeyResolver")))
@protocol SMSMutationKeyResolver
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)resolveIdentity:(SMSMutationKeyIdentity *)identity completionHandler:(void (^)(id<SMSStore6_coreStoreKey> _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("resolve(identity:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationParked")))
@interface SMSMutationParked : SMSBase <SMSMutationIntentEvent>
@property (readonly) SMSMutationFailure *failure __attribute__((swift_name("failure")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPendingState")))
@interface SMSMutationPendingState : SMSKotlinEnum<SMSMutationPendingState *>
@property (class, readonly) SMSMutationPendingState *pending __attribute__((swift_name("pending")));
@property (class, readonly) SMSMutationPendingState *inflight __attribute__((swift_name("inflight")));
@property (class, readonly) SMSMutationPendingState *refreshing __attribute__((swift_name("refreshing")));
@property (class, readonly) SMSMutationPendingState *adopting __attribute__((swift_name("adopting")));
@property (class, readonly) SMSMutationPendingState *applyingEffects __attribute__((swift_name("applyingEffects")));
@property (class, readonly) NSArray<SMSMutationPendingState *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationPendingState *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPreconditionCandidate")))
@interface SMSMutationPreconditionCandidate<K, V> : SMSBase
@property (readonly) id<SMSMutationPresence> base __attribute__((swift_name("base")));
@property (readonly) id<SMSStore6_coreStoreMeta> _Nullable capturedMeta __attribute__((swift_name("capturedMeta")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) K key __attribute__((swift_name("key")));
@property (readonly) id<SMSMutationPresence> mine __attribute__((swift_name("mine")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationPresence")))
@protocol SMSMutationPresence
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresenceAbsent")))
@interface SMSMutationPresenceAbsent : SMSBase <SMSMutationPresence>
@property (class, readonly, getter=shared) SMSMutationPresenceAbsent *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)absent __attribute__((swift_name("init()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresencePresent")))
@interface SMSMutationPresencePresent<V> : SMSBase <SMSMutationPresence>
@property (readonly) V value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(V)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresenceState")))
@interface SMSMutationPresenceState : SMSKotlinEnum<SMSMutationPresenceState *>
@property (class, readonly) SMSMutationPresenceState *present __attribute__((swift_name("present")));
@property (class, readonly) SMSMutationPresenceState *absent __attribute__((swift_name("absent")));
@property (class, readonly) NSArray<SMSMutationPresenceState *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationPresenceState *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPresentAck")))
@interface SMSMutationPresentAck<K, V> : SMSBase <SMSMutationAck>
@property (readonly) V authoritative __attribute__((swift_name("authoritative")));
@property (readonly) K _Nullable canonicalKey __attribute__((swift_name("canonicalKey")));
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
- (instancetype)initWithAuthoritative:(V)authoritative etag:(NSString * _Nullable)etag canonicalKey:(K _Nullable)canonicalKey __attribute__((swift_name("init(authoritative:etag:canonicalKey:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationPush")))
@interface SMSMutationPush<K, V> : SMSBase
@property (readonly) id<SMSMutationPresence> base __attribute__((swift_name("base")));
@property (readonly) id<SMSStore6_coreStoreMeta> _Nullable baseMeta __attribute__((swift_name("baseMeta")));
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) NSString *idempotencyKey __attribute__((swift_name("idempotencyKey")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) K key __attribute__((swift_name("key")));
@property (readonly) id<SMSMutationPresence> mine __attribute__((swift_name("mine")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t retiredThroughSequence __attribute__((swift_name("retiredThroughSequence")));
@property (readonly) int32_t valueCodecVersion __attribute__((swift_name("valueCodecVersion")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationRetired")))
@interface SMSMutationRetired : SMSBase <SMSMutationIntentEvent>
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationKeyIdentity *identity __attribute__((swift_name("identity")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) int64_t occurredAtEpochMillis __attribute__((swift_name("occurredAtEpochMillis")));
@property (readonly) int64_t retiredThroughSequence __attribute__((swift_name("retiredThroughSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationRetirement")))
@interface SMSMutationRetirement : SMSBase
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t retiredThroughSequence __attribute__((swift_name("retiredThroughSequence")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationRetirementAck")))
@interface SMSMutationRetirementAck : SMSBase
@property (readonly) int64_t confirmedThroughSequence __attribute__((swift_name("confirmedThroughSequence")));
- (instancetype)initWithConfirmedThroughSequence:(int64_t)confirmedThroughSequence __attribute__((swift_name("init(confirmedThroughSequence:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("MutationServer")))
@protocol SMSMutationServer
@required

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)pushRequest:(SMSMutationPush<id<SMSStore6_coreStoreKey>, id> *)request completionHandler:(void (^)(id<SMSMutationAck> _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("push(request:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)retireRequest:(SMSMutationRetirement *)request completionHandler:(void (^)(SMSMutationRetirementAck * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("retire(request:completionHandler:)")));
@end


/**
 * @note annotations
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreStore")))
@protocol SMSStore6_coreStore
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearKey:(id<SMSStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clear(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearNamespaceNamespace:(SMSStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearNamespace(namespace:completionHandler:)")));
- (void)close __attribute__((swift_name("close()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)getKey:(id<SMSStore6_coreStoreKey>)key freshness:(id<SMSStore6_coreFreshness>)freshness completionHandler:(void (^)(id _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("get(key:freshness:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateKey:(id<SMSStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidate(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateNamespaceNamespace:(SMSStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateNamespace(namespace:completionHandler:)")));
- (id<SMSKotlinx_coroutines_coreFlow>)streamKey:(id<SMSStore6_coreStoreKey>)key freshness:(id<SMSStore6_coreFreshness>)freshness __attribute__((swift_name("stream(key:freshness:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationStore")))
@interface SMSMutationStore<K, V> : SMSBase <SMSStore6_coreStore>
@property (readonly) id<SMSKotlinx_coroutines_coreSharedFlow> events __attribute__((swift_name("events")));
@property (readonly) id<SMSKotlinx_coroutines_coreFlow> keyEvents __attribute__((swift_name("keyEvents")));
@property (readonly) id<SMSKotlinx_coroutines_coreSharedFlow> poisoned __attribute__((swift_name("poisoned")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearKey:(K)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clear(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearNamespaceNamespace:(SMSStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearNamespace(namespace:completionHandler:)")));
- (void)close __attribute__((swift_name("close()")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deadLettersWithCompletionHandler:(void (^)(NSArray<SMSDeadLetter *> * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("deadLetters(completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)drainWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("drain(completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)drainKey:(K)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("drain(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)getKey:(K)key freshness:(id<SMSStore6_coreFreshness>)freshness completionHandler:(void (^)(V _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("get(key:freshness:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateKey:(K)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidate(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateNamespaceNamespace:(SMSStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateNamespace(namespace:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)mutateKey:(K)key ref:(SMSMutatorRef<K, V, id> *)ref args:(id)args completionHandler:(void (^)(NSString * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("mutate(key:ref:args:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)pendingKey:(K)key completionHandler:(void (^)(NSArray<SMSPendingIntent *> * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("pending(key:completionHandler:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)pendingWritesWithCompletionHandler:(void (^)(NSArray<SMSPendingIntent *> * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("pendingWrites(completionHandler:)")));
- (id<SMSKotlinx_coroutines_coreFlow>)streamKey:(K)key freshness:(id<SMSStore6_coreFreshness>)freshness __attribute__((swift_name("stream(key:freshness:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationStoreBuilder")))
@interface SMSMutationStoreBuilder<K, V> : SMSBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)bookkeeperBookkeeper:(id<SMSStore6_coreBookkeeper>)bookkeeper __attribute__((swift_name("bookkeeper(bookkeeper:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)conflictsConfigure:(void (^)(SMSMutationConflictBuilder<K, V> *))configure __attribute__((swift_name("conflicts(configure:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherFetch:(id<SMSKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcher(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherFetcher:(id<SMSStore6_coreFetcher>)fetcher __attribute__((swift_name("fetcher(fetcher:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherOfResultFetch:(id<SMSKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcherOfResult(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)freshnessValidatorValidator:(id<SMSStore6_coreFreshnessValidator>)validator __attribute__((swift_name("freshnessValidator(validator:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)journalStorageStorage:(id<SMSMutationJournalStorage>)storage __attribute__((swift_name("journalStorage(storage:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)maxIdleKeysCount:(int32_t)count __attribute__((swift_name("maxIdleKeys(count:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)persistenceSot:(id<SMSStore6_coreSourceOfTruth>)sot __attribute__((swift_name("persistence(sot:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)telemetryTelemetry:(id<SMSStore6_coreStoreTelemetry>)telemetry __attribute__((swift_name("telemetry(telemetry:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)wallClockWallClock:(id<SMSStore6_coreWallClock>)wallClock __attribute__((swift_name("wallClock(wallClock:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRef")))
@interface SMSMutatorRef<K, V, A> : SMSBase
@property (readonly) NSString *id __attribute__((swift_name("id")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRegistry")))
@interface SMSMutatorRegistry<K, V> : SMSBase
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRegistryBuilder")))
@interface SMSMutatorRegistryBuilder<K, V> : SMSBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (SMSMutatorRef<K, V, id> *)createId:(NSString *)id version:(int32_t)version codec:(id<SMSMutationCodec>)codec stales:(SMSStaleSet<K> *(^)(K, id))stales project:(V (^)(id))project __attribute__((swift_name("create(id:version:codec:stales:project:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (SMSMutatorRef<K, V, SMSKotlinUnit *> *)deleteId:(NSString *)id stales:(SMSStaleSet<K> *(^)(K, SMSKotlinUnit *))stales __attribute__((swift_name("delete(id:stales:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (SMSMutatorRef<K, V, id> *)mutatorId:(NSString *)id version:(int32_t)version codec:(id<SMSMutationCodec>)codec stales:(SMSStaleSet<K> *(^)(K, id))stales project:(id<SMSMutationPresence> _Nullable (^)(id<SMSMutationPresence>, id))project __attribute__((swift_name("mutator(id:version:codec:stales:project:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (SMSMutatorRef<K, V, id> *)updateId:(NSString *)id version:(int32_t)version codec:(id<SMSMutationCodec>)codec stales:(SMSStaleSet<K> *(^)(K, id))stales project:(V (^)(V, id))project __attribute__((swift_name("update(id:version:codec:stales:project:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (SMSMutatorRef<K, V, id> *)upsertId:(NSString *)id version:(int32_t)version codec:(id<SMSMutationCodec>)codec stales:(SMSStaleSet<K> *(^)(K, id))stales project:(id<SMSMutationPresence> (^)(id<SMSMutationPresence>, id))project __attribute__((swift_name("upsert(id:version:codec:stales:project:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PendingIntent")))
@interface SMSPendingIntent : SMSBase
@property (readonly) int32_t attempt __attribute__((swift_name("attempt")));
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));
@property (readonly) int64_t createdAtEpochMillis __attribute__((swift_name("createdAtEpochMillis")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));
@property (readonly) SMSMutationPendingState *state __attribute__((swift_name("state")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PoisonedIntent")))
@interface SMSPoisonedIntent : SMSBase
@property (readonly) SMSKotlinThrowable *failure __attribute__((swift_name("failure")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StaleSet")))
@interface SMSStaleSet<K> : SMSBase
@property (readonly) NSSet<K> *keys __attribute__((swift_name("keys")));
@property (readonly) NSSet<SMSStore6_coreStoreNamespace *> *namespaces __attribute__((swift_name("namespaces")));
- (instancetype)initWithKeys:(NSSet<K> *)keys namespaces:(NSSet<SMSStore6_coreStoreNamespace *> *)namespaces __attribute__((swift_name("init(keys:namespaces:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("MutationJournalStorage")))
@protocol SMSMutationJournalStorage
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)transactionBlock:(id _Nullable (^)(id<SMSMutationJournalTransaction>))block completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("transaction(block:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InMemoryMutationJournalStorage")))
@interface SMSInMemoryMutationJournalStorage : SMSBase <SMSMutationJournalStorage>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)transactionBlock:(id _Nullable (^)(id<SMSMutationJournalTransaction>))block completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("transaction(block:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAckRecord")))
@interface SMSMutationAckRecord : SMSBase
@property (readonly) SMSKotlinByteArray * _Nullable authoritativeBlob __attribute__((swift_name("authoritativeBlob")));
@property (readonly) SMSMutationPresenceState *authoritativePresence __attribute__((swift_name("authoritativePresence")));
@property (readonly) NSString * _Nullable canonicalTargetId __attribute__((swift_name("canonicalTargetId")));
@property (readonly) NSString * _Nullable canonicalTargetNamespace __attribute__((swift_name("canonicalTargetNamespace")));
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) int64_t receivedAt __attribute__((swift_name("receivedAt")));
@property (readonly) int32_t valueCodecVersion __attribute__((swift_name("valueCodecVersion")));
- (instancetype)initWithClientId:(NSString *)clientId clientSequence:(int64_t)clientSequence generation:(int32_t)generation authoritativePresence:(SMSMutationPresenceState *)authoritativePresence authoritativeBlob:(SMSKotlinByteArray * _Nullable)authoritativeBlob valueCodecVersion:(int32_t)valueCodecVersion etag:(NSString * _Nullable)etag canonicalTargetNamespace:(NSString * _Nullable)canonicalTargetNamespace canonicalTargetId:(NSString * _Nullable)canonicalTargetId receivedAt:(int64_t)receivedAt __attribute__((swift_name("init(clientId:clientSequence:generation:authoritativePresence:authoritativeBlob:valueCodecVersion:etag:canonicalTargetNamespace:canonicalTargetId:receivedAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAliasState")))
@interface SMSMutationAliasState : SMSKotlinEnum<SMSMutationAliasState *>
@property (class, readonly) SMSMutationAliasState *pending __attribute__((swift_name("pending")));
@property (class, readonly) SMSMutationAliasState *active __attribute__((swift_name("active")));
@property (class, readonly) NSArray<SMSMutationAliasState *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationAliasState *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationAttemptRecord")))
@interface SMSMutationAttemptRecord : SMSBase
@property (readonly) int64_t advertisedRetiredThroughSequence __attribute__((swift_name("advertisedRetiredThroughSequence")));
@property (readonly) SMSKotlinByteArray * _Nullable baseBlob __attribute__((swift_name("baseBlob")));
@property (readonly) SMSMutationPresenceState *basePresence __attribute__((swift_name("basePresence")));
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) NSString * _Nullable conflictEtag __attribute__((swift_name("conflictEtag")));
@property (readonly) SMSBoolean * _Nullable conflictMetaPresent __attribute__((swift_name("conflictMetaPresent")));
@property (readonly) SMSLong * _Nullable conflictReceivedAt __attribute__((swift_name("conflictReceivedAt")));
@property (readonly) SMSLong * _Nullable conflictWrittenAt __attribute__((swift_name("conflictWrittenAt")));
@property (readonly) NSString *effectiveCanonicalId __attribute__((swift_name("effectiveCanonicalId")));
@property (readonly) NSString *effectiveNamespace __attribute__((swift_name("effectiveNamespace")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) NSString *generationIdempotencyKey __attribute__((swift_name("generationIdempotencyKey")));
@property (readonly) SMSKotlinByteArray * _Nullable mineBlob __attribute__((swift_name("mineBlob")));
@property (readonly) SMSMutationPresenceState *minePresence __attribute__((swift_name("minePresence")));
@property (readonly) NSString * _Nullable preconditionEtag __attribute__((swift_name("preconditionEtag")));
@property (readonly) BOOL preconditionMetaPresent __attribute__((swift_name("preconditionMetaPresent")));
@property (readonly) SMSLong * _Nullable preconditionWrittenAt __attribute__((swift_name("preconditionWrittenAt")));
@property (readonly) int64_t preparedAt __attribute__((swift_name("preparedAt")));
@property (readonly) int32_t valueCodecVersion __attribute__((swift_name("valueCodecVersion")));
- (instancetype)initWithClientId:(NSString *)clientId clientSequence:(int64_t)clientSequence generation:(int32_t)generation effectiveNamespace:(NSString *)effectiveNamespace effectiveCanonicalId:(NSString *)effectiveCanonicalId valueCodecVersion:(int32_t)valueCodecVersion basePresence:(SMSMutationPresenceState *)basePresence baseBlob:(SMSKotlinByteArray * _Nullable)baseBlob minePresence:(SMSMutationPresenceState *)minePresence mineBlob:(SMSKotlinByteArray * _Nullable)mineBlob preconditionMetaPresent:(BOOL)preconditionMetaPresent preconditionWrittenAt:(SMSLong * _Nullable)preconditionWrittenAt preconditionEtag:(NSString * _Nullable)preconditionEtag advertisedRetiredThroughSequence:(int64_t)advertisedRetiredThroughSequence generationIdempotencyKey:(NSString *)generationIdempotencyKey preparedAt:(int64_t)preparedAt conflictMetaPresent:(SMSBoolean * _Nullable)conflictMetaPresent conflictWrittenAt:(SMSLong * _Nullable)conflictWrittenAt conflictEtag:(NSString * _Nullable)conflictEtag conflictReceivedAt:(SMSLong * _Nullable)conflictReceivedAt __attribute__((swift_name("init(clientId:clientSequence:generation:effectiveNamespace:effectiveCanonicalId:valueCodecVersion:basePresence:baseBlob:minePresence:mineBlob:preconditionMetaPresent:preconditionWrittenAt:preconditionEtag:advertisedRetiredThroughSequence:generationIdempotencyKey:preparedAt:conflictMetaPresent:conflictWrittenAt:conflictEtag:conflictReceivedAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationClientRecord")))
@interface SMSMutationClientRecord : SMSBase
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t createdAt __attribute__((swift_name("createdAt")));
@property (readonly) int64_t lastAllocatedSequence __attribute__((swift_name("lastAllocatedSequence")));
@property (readonly) int32_t recordVersion __attribute__((swift_name("recordVersion")));
@property (readonly) int64_t retiredThroughSequence __attribute__((swift_name("retiredThroughSequence")));
@property (readonly) int64_t serverConfirmedRetiredThroughSequence __attribute__((swift_name("serverConfirmedRetiredThroughSequence")));
- (instancetype)initWithRecordVersion:(int32_t)recordVersion clientId:(NSString *)clientId lastAllocatedSequence:(int64_t)lastAllocatedSequence retiredThroughSequence:(int64_t)retiredThroughSequence serverConfirmedRetiredThroughSequence:(int64_t)serverConfirmedRetiredThroughSequence createdAt:(int64_t)createdAt __attribute__((swift_name("init(recordVersion:clientId:lastAllocatedSequence:retiredThroughSequence:serverConfirmedRetiredThroughSequence:createdAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEffectDisposition")))
@interface SMSMutationEffectDisposition : SMSKotlinEnum<SMSMutationEffectDisposition *>
@property (class, readonly) SMSMutationEffectDisposition *pending __attribute__((swift_name("pending")));
@property (class, readonly) SMSMutationEffectDisposition *applied __attribute__((swift_name("applied")));
@property (class, readonly) SMSMutationEffectDisposition *skipped __attribute__((swift_name("skipped")));
@property (class, readonly) NSArray<SMSMutationEffectDisposition *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationEffectDisposition *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEffectKind")))
@interface SMSMutationEffectKind : SMSKotlinEnum<SMSMutationEffectKind *>
@property (class, readonly) SMSMutationEffectKind *key __attribute__((swift_name("key")));
@property (class, readonly) SMSMutationEffectKind *namespace_ __attribute__((swift_name("namespace_")));
@property (class, readonly) NSArray<SMSMutationEffectKind *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationEffectKind *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationEffectRecord")))
@interface SMSMutationEffectRecord : SMSBase
@property (readonly) NSString * _Nullable canonicalId __attribute__((swift_name("canonicalId")));
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) SMSLong * _Nullable completedAt __attribute__((swift_name("completedAt")));
@property (readonly) int64_t createdAt __attribute__((swift_name("createdAt")));
@property (readonly) SMSMutationEffectDisposition *disposition __attribute__((swift_name("disposition")));
@property (readonly) int32_t effectIndex __attribute__((swift_name("effectIndex")));
@property (readonly) SMSMutationEffectKind *kind __attribute__((swift_name("kind")));
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));
- (instancetype)initWithClientId:(NSString *)clientId clientSequence:(int64_t)clientSequence effectIndex:(int32_t)effectIndex kind:(SMSMutationEffectKind *)kind namespace:(NSString *)namespace_ canonicalId:(NSString * _Nullable)canonicalId createdAt:(int64_t)createdAt disposition:(SMSMutationEffectDisposition *)disposition completedAt:(SMSLong * _Nullable)completedAt __attribute__((swift_name("init(clientId:clientSequence:effectIndex:kind:namespace:canonicalId:createdAt:disposition:completedAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationExecutionPhase")))
@interface SMSMutationExecutionPhase : SMSKotlinEnum<SMSMutationExecutionPhase *>
@property (class, readonly) SMSMutationExecutionPhase *unprepared __attribute__((swift_name("unprepared")));
@property (class, readonly) SMSMutationExecutionPhase *ready __attribute__((swift_name("ready")));
@property (class, readonly) SMSMutationExecutionPhase *inflight __attribute__((swift_name("inflight")));
@property (class, readonly) SMSMutationExecutionPhase *refreshRequired __attribute__((swift_name("refreshRequired")));
@property (class, readonly) SMSMutationExecutionPhase *acked __attribute__((swift_name("acked")));
@property (class, readonly) SMSMutationExecutionPhase *effectsPending __attribute__((swift_name("effectsPending")));
@property (class, readonly) SMSMutationExecutionPhase *parked __attribute__((swift_name("parked")));
@property (class, readonly) SMSMutationExecutionPhase *retired __attribute__((swift_name("retired")));
@property (class, readonly) NSArray<SMSMutationExecutionPhase *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationExecutionPhase *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationExecutionRecord")))
@interface SMSMutationExecutionRecord : SMSBase
@property (readonly) SMSLong * _Nullable activeFailureId __attribute__((swift_name("activeFailureId")));
@property (readonly) int32_t attempt __attribute__((swift_name("attempt")));
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) int32_t currentGeneration __attribute__((swift_name("currentGeneration")));
@property (readonly) SMSLong * _Nullable lastAttemptAt __attribute__((swift_name("lastAttemptAt")));
@property (readonly) SMSMutationExecutionPhase *phase __attribute__((swift_name("phase")));
@property (readonly) SMSLong * _Nullable retiredAt __attribute__((swift_name("retiredAt")));
- (instancetype)initWithClientId:(NSString *)clientId clientSequence:(int64_t)clientSequence phase:(SMSMutationExecutionPhase *)phase currentGeneration:(int32_t)currentGeneration attempt:(int32_t)attempt lastAttemptAt:(SMSLong * _Nullable)lastAttemptAt activeFailureId:(SMSLong * _Nullable)activeFailureId retiredAt:(SMSLong * _Nullable)retiredAt __attribute__((swift_name("init(clientId:clientSequence:phase:currentGeneration:attempt:lastAttemptAt:activeFailureId:retiredAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationFailureRecord")))
@interface SMSMutationFailureRecord : SMSBase
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) NSString *detail __attribute__((swift_name("detail")));
@property (readonly) int64_t failureId __attribute__((swift_name("failureId")));
@property (readonly) int32_t generation __attribute__((swift_name("generation")));
@property (readonly) SMSMutationFailureKind *kind __attribute__((swift_name("kind")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) int64_t occurredAt __attribute__((swift_name("occurredAt")));
- (instancetype)initWithFailureId:(int64_t)failureId clientId:(NSString *)clientId clientSequence:(int64_t)clientSequence generation:(int32_t)generation kind:(SMSMutationFailureKind *)kind detail:(NSString *)detail message:(NSString *)message occurredAt:(int64_t)occurredAt __attribute__((swift_name("init(failureId:clientId:clientSequence:generation:kind:detail:message:occurredAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationIntentRecord")))
@interface SMSMutationIntentRecord : SMSBase
@property (readonly) SMSKotlinByteArray *argsBlob __attribute__((swift_name("argsBlob")));
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));
@property (readonly) NSString *clientId __attribute__((swift_name("clientId")));
@property (readonly) int64_t clientSequence __attribute__((swift_name("clientSequence")));
@property (readonly) int64_t createdAt __attribute__((swift_name("createdAt")));
@property (readonly) NSString *idempotencyRoot __attribute__((swift_name("idempotencyRoot")));
@property (readonly) NSString *mutationId __attribute__((swift_name("mutationId")));
@property (readonly) NSString *mutatorId __attribute__((swift_name("mutatorId")));
@property (readonly) int32_t mutatorVersion __attribute__((swift_name("mutatorVersion")));
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));
@property (readonly) int32_t recordVersion __attribute__((swift_name("recordVersion")));
@property (readonly) int64_t rowId __attribute__((swift_name("rowId")));
- (instancetype)initWithRowId:(int64_t)rowId recordVersion:(int32_t)recordVersion clientId:(NSString *)clientId clientSequence:(int64_t)clientSequence mutationId:(NSString *)mutationId namespace:(NSString *)namespace_ canonicalId:(NSString *)canonicalId mutatorId:(NSString *)mutatorId mutatorVersion:(int32_t)mutatorVersion argsBlob:(SMSKotlinByteArray *)argsBlob idempotencyRoot:(NSString *)idempotencyRoot createdAt:(int64_t)createdAt __attribute__((swift_name("init(rowId:recordVersion:clientId:clientSequence:mutationId:namespace:canonicalId:mutatorId:mutatorVersion:argsBlob:idempotencyRoot:createdAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("MutationJournalTransaction")))
@protocol SMSMutationJournalTransaction
@required
- (NSArray<SMSMutationAckRecord *> *)acksClientId:(NSString *)clientId __attribute__((swift_name("acks(clientId:)")));
- (void)advanceAliasRecord:(SMSMutationKeyAliasRecord *)record __attribute__((swift_name("advanceAlias(record:)")));
- (void)advanceClientRecord:(SMSMutationClientRecord *)record __attribute__((swift_name("advanceClient(record:)")));
- (void)advanceEffectRecord:(SMSMutationEffectRecord *)record __attribute__((swift_name("advanceEffect(record:)")));
- (void)advanceExecutionRecord:(SMSMutationExecutionRecord *)record __attribute__((swift_name("advanceExecution(record:)")));
- (void)advanceTombstoneRecord:(SMSMutationKeyTombstoneRecord *)record __attribute__((swift_name("advanceTombstone(record:)")));
- (NSArray<SMSMutationKeyAliasRecord *> *)aliases __attribute__((swift_name("aliases()")));
- (SMSMutationFailureRecord *)appendFailureClientId:(NSString *)clientId clientSequence:(int64_t)clientSequence generation:(int32_t)generation kind:(SMSMutationFailureKind *)kind detail:(NSString *)detail message:(NSString *)message occurredAt:(int64_t)occurredAt __attribute__((swift_name("appendFailure(clientId:clientSequence:generation:kind:detail:message:occurredAt:)")));
- (NSArray<SMSMutationAttemptRecord *> *)attemptsClientId:(NSString *)clientId __attribute__((swift_name("attempts(clientId:)")));
- (SMSMutationClientRecord * _Nullable)clientClientId:(NSString *)clientId __attribute__((swift_name("client(clientId:)")));
- (SMSMutationClientRecord *)confirmRetiredThroughClientId:(NSString *)clientId requestedThroughSequence:(int64_t)requestedThroughSequence serverConfirmedThroughSequence:(int64_t)serverConfirmedThroughSequence __attribute__((swift_name("confirmRetiredThrough(clientId:requestedThroughSequence:serverConfirmedThroughSequence:)")));
- (NSArray<SMSMutationEffectRecord *> *)effectsClientId:(NSString *)clientId __attribute__((swift_name("effects(clientId:)")));
- (NSArray<SMSMutationExecutionRecord *> *)executionsClientId:(NSString *)clientId __attribute__((swift_name("executions(clientId:)")));
- (NSArray<SMSMutationFailureRecord *> *)failuresClientId:(NSString *)clientId __attribute__((swift_name("failures(clientId:)")));
- (void)insertAckRecord:(SMSMutationAckRecord *)record __attribute__((swift_name("insertAck(record:)")));
- (void)insertAliasRecord:(SMSMutationKeyAliasRecord *)record __attribute__((swift_name("insertAlias(record:)")));
- (void)insertAttemptRecord:(SMSMutationAttemptRecord *)record __attribute__((swift_name("insertAttempt(record:)")));
- (void)insertClientRecord:(SMSMutationClientRecord *)record __attribute__((swift_name("insertClient(record:)")));
- (void)insertEffectRecord:(SMSMutationEffectRecord *)record __attribute__((swift_name("insertEffect(record:)")));
- (void)insertExecutionRecord:(SMSMutationExecutionRecord *)record __attribute__((swift_name("insertExecution(record:)")));
- (SMSMutationIntentRecord *)insertIntentRecordVersion:(int32_t)recordVersion clientId:(NSString *)clientId clientSequence:(int64_t)clientSequence mutationId:(NSString *)mutationId namespace:(NSString *)namespace_ canonicalId:(NSString *)canonicalId mutatorId:(NSString *)mutatorId mutatorVersion:(int32_t)mutatorVersion argsBlob:(SMSKotlinByteArray *)argsBlob idempotencyRoot:(NSString *)idempotencyRoot createdAt:(int64_t)createdAt __attribute__((swift_name("insertIntent(recordVersion:clientId:clientSequence:mutationId:namespace:canonicalId:mutatorId:mutatorVersion:argsBlob:idempotencyRoot:createdAt:)")));
- (void)insertTombstoneRecord:(SMSMutationKeyTombstoneRecord *)record __attribute__((swift_name("insertTombstone(record:)")));
- (NSArray<SMSMutationIntentRecord *> *)intentsClientId:(NSString *)clientId __attribute__((swift_name("intents(clientId:)")));
- (void)pruneClientId:(NSString *)clientId serverConfirmedRetiredThroughSequence:(int64_t)serverConfirmedRetiredThroughSequence __attribute__((swift_name("prune(clientId:serverConfirmedRetiredThroughSequence:)")));
- (void)recordConflictReceiptRecord:(SMSMutationAttemptRecord *)record __attribute__((swift_name("recordConflictReceipt(record:)")));
- (NSArray<SMSMutationKeyTombstoneRecord *> *)tombstones __attribute__((swift_name("tombstones()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationKeyAliasRecord")))
@interface SMSMutationKeyAliasRecord : SMSBase
@property (readonly) SMSLong * _Nullable activatedAt __attribute__((swift_name("activatedAt")));
@property (readonly) int64_t createdAt __attribute__((swift_name("createdAt")));
@property (readonly) NSString *createdByClientId __attribute__((swift_name("createdByClientId")));
@property (readonly) int64_t createdBySequence __attribute__((swift_name("createdBySequence")));
@property (readonly) NSString *sourceCanonicalId __attribute__((swift_name("sourceCanonicalId")));
@property (readonly) NSString *sourceNamespace __attribute__((swift_name("sourceNamespace")));
@property (readonly) SMSMutationAliasState *state __attribute__((swift_name("state")));
@property (readonly) NSString *targetCanonicalId __attribute__((swift_name("targetCanonicalId")));
@property (readonly) NSString *targetNamespace __attribute__((swift_name("targetNamespace")));
- (instancetype)initWithSourceNamespace:(NSString *)sourceNamespace sourceCanonicalId:(NSString *)sourceCanonicalId targetNamespace:(NSString *)targetNamespace targetCanonicalId:(NSString *)targetCanonicalId state:(SMSMutationAliasState *)state createdByClientId:(NSString *)createdByClientId createdBySequence:(int64_t)createdBySequence createdAt:(int64_t)createdAt activatedAt:(SMSLong * _Nullable)activatedAt __attribute__((swift_name("init(sourceNamespace:sourceCanonicalId:targetNamespace:targetCanonicalId:state:createdByClientId:createdBySequence:createdAt:activatedAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationKeyTombstoneRecord")))
@interface SMSMutationKeyTombstoneRecord : SMSBase
@property (readonly) SMSLong * _Nullable activatedAt __attribute__((swift_name("activatedAt")));
@property (readonly) NSString *canonicalId __attribute__((swift_name("canonicalId")));
@property (readonly) int64_t createdAt __attribute__((swift_name("createdAt")));
@property (readonly) NSString *createdByClientId __attribute__((swift_name("createdByClientId")));
@property (readonly) int64_t createdBySequence __attribute__((swift_name("createdBySequence")));
@property (readonly, getter=namespace) NSString *namespace_ __attribute__((swift_name("namespace_")));
@property (readonly) SMSMutationTombstoneState *state __attribute__((swift_name("state")));
@property (readonly) SMSLong * _Nullable supersededAt __attribute__((swift_name("supersededAt")));
@property (readonly) NSString * _Nullable supersededByClientId __attribute__((swift_name("supersededByClientId")));
@property (readonly) SMSLong * _Nullable supersededBySequence __attribute__((swift_name("supersededBySequence")));
- (instancetype)initWithNamespace:(NSString *)namespace_ canonicalId:(NSString *)canonicalId createdByClientId:(NSString *)createdByClientId createdBySequence:(int64_t)createdBySequence state:(SMSMutationTombstoneState *)state createdAt:(int64_t)createdAt activatedAt:(SMSLong * _Nullable)activatedAt supersededByClientId:(NSString * _Nullable)supersededByClientId supersededBySequence:(SMSLong * _Nullable)supersededBySequence supersededAt:(SMSLong * _Nullable)supersededAt __attribute__((swift_name("init(namespace:canonicalId:createdByClientId:createdBySequence:state:createdAt:activatedAt:supersededByClientId:supersededBySequence:supersededAt:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationTombstoneState")))
@interface SMSMutationTombstoneState : SMSKotlinEnum<SMSMutationTombstoneState *>
@property (class, readonly) SMSMutationTombstoneState *pending __attribute__((swift_name("pending")));
@property (class, readonly) SMSMutationTombstoneState *active __attribute__((swift_name("active")));
@property (class, readonly) SMSMutationTombstoneState *superseded __attribute__((swift_name("superseded")));
@property (class, readonly) NSArray<SMSMutationTombstoneState *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSMutationTombstoneState *> *)values __attribute__((swift_name("values()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutationStoreKt")))
@interface SMSMutationStoreKt : SMSBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
+ (SMSMutationStore<id<SMSStore6_coreStoreKey>, id> *)mutationStoreRegistry:(SMSMutatorRegistry<id<SMSStore6_coreStoreKey>, id> *)registry server:(id<SMSMutationServer>)server keyResolver:(id<SMSMutationKeyResolver>)keyResolver valueCodecVersion:(int32_t)valueCodecVersion valueCodec:(id<SMSMutationCodec>)valueCodec configure:(void (^)(SMSMutationStoreBuilder<id<SMSStore6_coreStoreKey>, id> *))configure __attribute__((swift_name("mutationStore(registry:server:keyResolver:valueCodecVersion:valueCodec:configure:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MutatorRegistryKt")))
@interface SMSMutatorRegistryKt : SMSBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
+ (SMSMutatorRegistry<id<SMSStore6_coreStoreKey>, id> *)mutatorRegistryConfigure:(void (^)(SMSMutatorRegistryBuilder<id<SMSStore6_coreStoreKey>, id> *))configure __attribute__((swift_name("mutatorRegistry(configure:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("__SkieSuspendWrappersKt")))
@interface SMS__SkieSuspendWrappersKt : SMSBase
+ (void)Skie_Suspend__0__resolveDispatchReceiver:(id<SMSMutationKeyResolver>)dispatchReceiver identity:(SMSMutationKeyIdentity *)identity suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__0__resolve(dispatchReceiver:identity:suspendHandler:)")));
+ (void)Skie_Suspend__10__emitDispatchReceiver:(id<SMSKotlinx_coroutines_coreFlowCollector>)dispatchReceiver value:(id _Nullable)value suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__10__emit(dispatchReceiver:value:suspendHandler:)")));
+ (void)Skie_Suspend__11__clearDispatchReceiver:(id<SMSStore6_coreStore>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__11__clear(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__12__clearAllDispatchReceiver:(id<SMSStore6_coreStore>)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__12__clearAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__13__clearNamespaceDispatchReceiver:(id<SMSStore6_coreStore>)dispatchReceiver namespace:(SMSStore6_coreStoreNamespace *)namespace_ suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__13__clearNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__14__getDispatchReceiver:(id<SMSStore6_coreStore>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key freshness:(id<SMSStore6_coreFreshness>)freshness suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__14__get(dispatchReceiver:key:freshness:suspendHandler:)")));
+ (void)Skie_Suspend__15__invalidateDispatchReceiver:(id<SMSStore6_coreStore>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__15__invalidate(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__16__invalidateAllDispatchReceiver:(id<SMSStore6_coreStore>)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__16__invalidateAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__17__invalidateNamespaceDispatchReceiver:(id<SMSStore6_coreStore>)dispatchReceiver namespace:(SMSStore6_coreStoreNamespace *)namespace_ suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__17__invalidateNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__18__deleteDispatchReceiver:(id<SMSStore6_coreSourceOfTruth>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__18__delete(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__19__deleteAllDispatchReceiver:(id<SMSStore6_coreSourceOfTruth>)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__19__deleteAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__1__pushDispatchReceiver:(id<SMSMutationServer>)dispatchReceiver request:(SMSMutationPush<id<SMSStore6_coreStoreKey>, id> *)request suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__1__push(dispatchReceiver:request:suspendHandler:)")));
+ (void)Skie_Suspend__20__deleteNamespaceDispatchReceiver:(id<SMSStore6_coreSourceOfTruth>)dispatchReceiver namespace:(SMSStore6_coreStoreNamespace *)namespace_ suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__20__deleteNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__21__writeDispatchReceiver:(id<SMSStore6_coreSourceOfTruth>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key value:(id)value suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__21__write(dispatchReceiver:key:value:suspendHandler:)")));
+ (void)Skie_Suspend__22__transactionDispatchReceiver:(id<SMSMutationJournalStorage>)dispatchReceiver block:(id _Nullable (^)(id<SMSMutationJournalTransaction>))block suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__22__transaction(dispatchReceiver:block:suspendHandler:)")));
+ (void)Skie_Suspend__23__invokeDispatchReceiver:(id<SMSKotlinSuspendFunction1>)dispatchReceiver p1:(id _Nullable)p1 suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__23__invoke(dispatchReceiver:p1:suspendHandler:)")));
+ (void)Skie_Suspend__24__fetchDispatchReceiver:(id<SMSStore6_coreFetcher>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key etag:(NSString * _Nullable)etag suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__24__fetch(dispatchReceiver:key:etag:suspendHandler:)")));
+ (void)Skie_Suspend__25__advanceGlobalStaleWatermarkDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__25__advanceGlobalStaleWatermark(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__26__advanceStaleWatermarkDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver namespace:(SMSStore6_coreStoreNamespace *)namespace_ suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__26__advanceStaleWatermark(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__27__forgetDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__27__forget(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__28__forgetAllDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__28__forgetAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__29__forgetNamespaceDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver namespace:(SMSStore6_coreStoreNamespace *)namespace_ suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__29__forgetNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__2__retireDispatchReceiver:(id<SMSMutationServer>)dispatchReceiver request:(SMSMutationRetirement *)request suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__2__retire(dispatchReceiver:request:suspendHandler:)")));
+ (void)Skie_Suspend__30__markStaleDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__30__markStale(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__31__recordFailureDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key atEpochMillis:(int64_t)atEpochMillis suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__31__recordFailure(dispatchReceiver:key:atEpochMillis:suspendHandler:)")));
+ (void)Skie_Suspend__32__recordSuccessDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key meta:(id<SMSStore6_coreStoreMeta>)meta suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__32__recordSuccess(dispatchReceiver:key:meta:suspendHandler:)")));
+ (void)Skie_Suspend__33__statusDispatchReceiver:(id<SMSStore6_coreBookkeeper>)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__33__status(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__34__hasNextDispatchReceiver:(SMSSkieColdFlowIterator<id> *)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__34__hasNext(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__3__deadLettersDispatchReceiver:(SMSMutationStore<id<SMSStore6_coreStoreKey>, id> *)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__3__deadLetters(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__4__drainDispatchReceiver:(SMSMutationStore<id<SMSStore6_coreStoreKey>, id> *)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__4__drain(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__5__drainDispatchReceiver:(SMSMutationStore<id<SMSStore6_coreStoreKey>, id> *)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__5__drain(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__6__mutateDispatchReceiver:(SMSMutationStore<id<SMSStore6_coreStoreKey>, id> *)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key ref:(SMSMutatorRef<id<SMSStore6_coreStoreKey>, id, id> *)ref args:(id)args suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__6__mutate(dispatchReceiver:key:ref:args:suspendHandler:)")));
+ (void)Skie_Suspend__7__pendingDispatchReceiver:(SMSMutationStore<id<SMSStore6_coreStoreKey>, id> *)dispatchReceiver key:(id<SMSStore6_coreStoreKey>)key suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__7__pending(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__8__pendingWritesDispatchReceiver:(SMSMutationStore<id<SMSStore6_coreStoreKey>, id> *)dispatchReceiver suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__8__pendingWrites(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__9__collectDispatchReceiver:(id<SMSKotlinx_coroutines_coreFlow>)dispatchReceiver collector:(id<SMSKotlinx_coroutines_coreFlowCollector>)collector suspendHandler:(SMSSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__9__collect(dispatchReceiver:collector:suspendHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("__SkieTypeExportsKt")))
@interface SMS__SkieTypeExportsKt : SMSBase
+ (void)skieTypeExports_0P0:(SMSStore6_coreFreshnessCachedOrFetch *)p0 p1:(SMSStore6_coreFreshnessLocalOnly *)p1 p2:(SMSStore6_coreFreshnessMaxAge *)p2 p3:(SMSStore6_coreFreshnessMustBeFresh *)p3 p4:(SMSStore6_coreFreshnessStaleIfError *)p4 p5:(SMSStore6_coreStoreErrorConflict *)p5 p6:(SMSStore6_coreStoreErrorConversion *)p6 p7:(SMSStore6_coreStoreErrorFetch *)p7 p8:(SMSStore6_coreStoreErrorFreshnessUnsatisfiable *)p8 p9:(SMSStore6_coreStoreErrorMissing *)p9 p10:(SMSStore6_coreStoreErrorPersistence *)p10 p11:(id<SMSStore6_coreStoreResult>)p11 p12:(SMSStore6_coreFetchPlanConditional *)p12 p13:(SMSStore6_coreFetchPlanFetch *)p13 p14:(SMSStore6_coreFetchPlanSkip *)p14 p15:(SMSStore6_coreFetcherResultDeleted *)p15 p16:(SMSStore6_coreFetcherResultError *)p16 p17:(SMSStore6_coreFetcherResultNotModified *)p17 p18:(SMSStore6_coreFetcherResultSuccess<id> *)p18 p19:(SMSStore6_coreKeyEvents *)p19 __attribute__((swift_name("skieTypeExports_0(p0:p1:p2:p3:p4:p5:p6:p7:p8:p9:p10:p11:p12:p13:p14:p15:p16:p17:p18:p19:)")));
+ (void)skieTypeExports_1P0:(SMSStore6_coreStoreResultData<id> *)p0 p1:(SMSStore6_coreStoreResultError *)p1 p2:(SMSStore6_coreStoreResultLoading *)p2 p3:(SMSStore6_coreStoreResultRevalidated *)p3 __attribute__((swift_name("skieTypeExports_1(p0:p1:p2:p3:)")));
@end

__attribute__((swift_name("KotlinThrowable")))
@interface SMSKotlinThrowable : SMSBase
@property (readonly) SMSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString * _Nullable message __attribute__((swift_name("message")));
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   kotlin.experimental.ExperimentalNativeApi
*/
- (SMSKotlinArray<NSString *> *)getStackTrace __attribute__((swift_name("getStackTrace()")));
- (void)printStackTrace __attribute__((swift_name("printStackTrace()")));
- (NSString *)description __attribute__((swift_name("description()")));
- (NSError *)asError __attribute__((swift_name("asError()")));
@end

__attribute__((swift_name("KotlinException")))
@interface SMSKotlinException : SMSKotlinThrowable
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinRuntimeException")))
@interface SMSKotlinRuntimeException : SMSKotlinException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinIllegalStateException")))
@interface SMSKotlinIllegalStateException : SMSKotlinRuntimeException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   kotlin.SinceKotlin(version="1.4")
*/
__attribute__((swift_name("KotlinCancellationException")))
@interface SMSKotlinCancellationException : SMSKotlinIllegalStateException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(SMSKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreRunnable")))
@protocol SMSKotlinx_coroutines_coreRunnable
@required
- (void)run __attribute__((swift_name("run()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinByteArray")))
@interface SMSKotlinByteArray : SMSBase
@property (readonly) int32_t size __attribute__((swift_name("size")));
+ (instancetype)arrayWithSize:(int32_t)size __attribute__((swift_name("init(size:)")));
+ (instancetype)arrayWithSize:(int32_t)size init:(SMSByte *(^)(SMSInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (int8_t)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (SMSKotlinByteIterator *)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(int8_t)value __attribute__((swift_name("set(index:value:)")));
@end

__attribute__((swift_name("Store6_coreStoreMeta")))
@protocol SMSStore6_coreStoreMeta
@required
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) int64_t writtenAtEpochMillis __attribute__((swift_name("writtenAtEpochMillis")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinEnumCompanion")))
@interface SMSKotlinEnumCompanion : SMSBase
@property (class, readonly, getter=shared) SMSKotlinEnumCompanion *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinArray")))
@interface SMSKotlinArray<T> : SMSBase
@property (readonly) int32_t size __attribute__((swift_name("size")));
+ (instancetype)arrayWithSize:(int32_t)size init:(T _Nullable (^)(SMSInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (T _Nullable)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (id<SMSKotlinIterator>)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(T _Nullable)value __attribute__((swift_name("set(index:value:)")));
@end

__attribute__((swift_name("Store6_coreStoreKey")))
@protocol SMSStore6_coreStoreKey
@required
- (NSString *)canonicalId __attribute__((swift_name("canonicalId()")));
@property (readonly, getter=namespace) SMSStore6_coreStoreNamespace *namespace_ __attribute__((swift_name("namespace_")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreNamespace")))
@interface SMSStore6_coreStoreNamespace : SMSBase
@property (readonly) NSString *value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Store6_coreFreshness")))
@protocol SMSStore6_coreFreshness
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreBookkeeper")))
@protocol SMSStore6_coreBookkeeper
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)advanceGlobalStaleWatermarkWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("advanceGlobalStaleWatermark(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)advanceStaleWatermarkNamespace:(SMSStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("advanceStaleWatermark(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetKey:(id<SMSStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forget(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetNamespaceNamespace:(SMSStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetNamespace(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)markStaleKey:(id<SMSStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("markStale(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordFailureKey:(id<SMSStore6_coreStoreKey>)key atEpochMillis:(int64_t)atEpochMillis completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordFailure(key:atEpochMillis:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordSuccessKey:(id<SMSStore6_coreStoreKey>)key meta:(id<SMSStore6_coreStoreMeta>)meta completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordSuccess(key:meta:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)statusKey:(id<SMSStore6_coreStoreKey>)key completionHandler:(void (^)(SMSStore6_coreKeyStatus * _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("status(key:completionHandler:)")));
@end

__attribute__((swift_name("KotlinFunction")))
@protocol SMSKotlinFunction
@required
@end

__attribute__((swift_name("KotlinSuspendFunction1")))
@protocol SMSKotlinSuspendFunction1 <SMSKotlinFunction>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invokeP1:(id _Nullable)p1 completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("invoke(p1:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreFetcher")))
@protocol SMSStore6_coreFetcher
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)fetchKey:(id<SMSStore6_coreStoreKey>)key etag:(NSString * _Nullable)etag completionHandler:(void (^)(id<SMSStore6_coreFetcherResult> _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("fetch(key:etag:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreFreshnessValidator")))
@protocol SMSStore6_coreFreshnessValidator
@required
- (id<SMSStore6_coreFetchPlan>)planContext:(SMSStore6_coreFreshnessContext *)context __attribute__((swift_name("plan(context:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreSourceOfTruth")))
@protocol SMSStore6_coreSourceOfTruth
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteKey:(id<SMSStore6_coreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("delete(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteNamespaceNamespace:(SMSStore6_coreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteNamespace(namespace:completionHandler:)")));
- (id<SMSKotlinx_coroutines_coreFlow>)readerKey:(id<SMSStore6_coreStoreKey>)key __attribute__((swift_name("reader(key:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)writeKey:(id<SMSStore6_coreStoreKey>)key value:(id)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("write(key:value:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreStoreTelemetry")))
@protocol SMSStore6_coreStoreTelemetry
@required
- (void)onClearedKey:(id<SMSStore6_coreStoreKey>)key __attribute__((swift_name("onCleared(key:)")));
- (void)onFetchFailedKey:(id<SMSStore6_coreStoreKey>)key error:(SMSStore6_coreStoreError *)error duration:(int64_t)duration __attribute__((swift_name("onFetchFailed(key:error:duration:)")));
- (void)onFetchStartedKey:(id<SMSStore6_coreStoreKey>)key __attribute__((swift_name("onFetchStarted(key:)")));
- (void)onFetchSucceededKey:(id<SMSStore6_coreStoreKey>)key duration:(int64_t)duration __attribute__((swift_name("onFetchSucceeded(key:duration:)")));
- (void)onInvalidatedKey:(id<SMSStore6_coreStoreKey>)key __attribute__((swift_name("onInvalidated(key:)")));
- (void)onServeKey:(id<SMSStore6_coreStoreKey>)key origin:(SMSStore6_coreOrigin *)origin __attribute__((swift_name("onServe(key:origin:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store6_coreWallClock")))
@protocol SMSStore6_coreWallClock
@required
- (int64_t)nowEpochMillis __attribute__((swift_name("nowEpochMillis()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinUnit")))
@interface SMSKotlinUnit : SMSBase
@property (class, readonly, getter=shared) SMSKotlinUnit *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)unit __attribute__((swift_name("init()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFreshnessCachedOrFetch")))
@interface SMSStore6_coreFreshnessCachedOrFetch : SMSBase <SMSStore6_coreFreshness>
@property (class, readonly, getter=shared) SMSStore6_coreFreshnessCachedOrFetch *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)cachedOrFetch __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFreshnessLocalOnly")))
@interface SMSStore6_coreFreshnessLocalOnly : SMSBase <SMSStore6_coreFreshness>
@property (class, readonly, getter=shared) SMSStore6_coreFreshnessLocalOnly *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)localOnly __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFreshnessMaxAge")))
@interface SMSStore6_coreFreshnessMaxAge : SMSBase <SMSStore6_coreFreshness>
@property (readonly) int64_t notOlderThan __attribute__((swift_name("notOlderThan")));
- (instancetype)initWithNotOlderThan:(int64_t)notOlderThan __attribute__((swift_name("init(notOlderThan:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFreshnessMustBeFresh")))
@interface SMSStore6_coreFreshnessMustBeFresh : SMSBase <SMSStore6_coreFreshness>
@property (class, readonly, getter=shared) SMSStore6_coreFreshnessMustBeFresh *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)mustBeFresh __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFreshnessStaleIfError")))
@interface SMSStore6_coreFreshnessStaleIfError : SMSBase <SMSStore6_coreFreshness>
@property (class, readonly, getter=shared) SMSStore6_coreFreshnessStaleIfError *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)staleIfError __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("Store6_coreStoreError")))
@interface SMSStore6_coreStoreError : SMSBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreError.Conflict")))
@interface SMSStore6_coreStoreErrorConflict : SMSStore6_coreStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) id<SMSStore6_coreStoreMeta> _Nullable serverMeta __attribute__((swift_name("serverMeta")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreError.Conversion")))
@interface SMSStore6_coreStoreErrorConversion : SMSStore6_coreStoreError
@property (readonly) SMSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreError.Fetch")))
@interface SMSStore6_coreStoreErrorFetch : SMSStore6_coreStoreError
@property (readonly) SMSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreError.FreshnessUnsatisfiable")))
@interface SMSStore6_coreStoreErrorFreshnessUnsatisfiable : SMSStore6_coreStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreError.Missing")))
@interface SMSStore6_coreStoreErrorMissing : SMSStore6_coreStoreError
@property (readonly) id<SMSStore6_coreStoreKey> key __attribute__((swift_name("key")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreError.Persistence")))
@interface SMSStore6_coreStoreErrorPersistence : SMSStore6_coreStoreError
@property (readonly) SMSKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((swift_name("Store6_coreStoreResult")))
@protocol SMSStore6_coreStoreResult
@required
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("Store6_coreFetchPlan")))
@protocol SMSStore6_coreFetchPlan
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFetchPlanConditional")))
@interface SMSStore6_coreFetchPlanConditional : SMSBase <SMSStore6_coreFetchPlan>
@property (readonly) NSString *etag __attribute__((swift_name("etag")));
@property (readonly) BOOL servesResidentWhileFetching __attribute__((swift_name("servesResidentWhileFetching")));
- (instancetype)initWithEtag:(NSString *)etag servesResidentWhileFetching:(BOOL)servesResidentWhileFetching __attribute__((swift_name("init(etag:servesResidentWhileFetching:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFetchPlanFetch")))
@interface SMSStore6_coreFetchPlanFetch : SMSBase <SMSStore6_coreFetchPlan>
@property (readonly) BOOL servesResidentWhileFetching __attribute__((swift_name("servesResidentWhileFetching")));
- (instancetype)initWithServesResidentWhileFetching:(BOOL)servesResidentWhileFetching __attribute__((swift_name("init(servesResidentWhileFetching:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFetchPlanSkip")))
@interface SMSStore6_coreFetchPlanSkip : SMSBase <SMSStore6_coreFetchPlan>
@property (class, readonly, getter=shared) SMSStore6_coreFetchPlanSkip *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)skip __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("Store6_coreFetcherResult")))
@protocol SMSStore6_coreFetcherResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFetcherResultDeleted")))
@interface SMSStore6_coreFetcherResultDeleted : SMSBase <SMSStore6_coreFetcherResult>
@property (class, readonly, getter=shared) SMSStore6_coreFetcherResultDeleted *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)deleted __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFetcherResultError")))
@interface SMSStore6_coreFetcherResultError : SMSBase <SMSStore6_coreFetcherResult>
@property (readonly) SMSKotlinThrowable *cause __attribute__((swift_name("cause")));
- (instancetype)initWithCause:(SMSKotlinThrowable *)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFetcherResultNotModified")))
@interface SMSStore6_coreFetcherResultNotModified : SMSBase <SMSStore6_coreFetcherResult>
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
- (instancetype)initWithEtag:(NSString * _Nullable)etag __attribute__((swift_name("init(etag:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFetcherResultSuccess")))
@interface SMSStore6_coreFetcherResultSuccess<V> : SMSBase <SMSStore6_coreFetcherResult>
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) V value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(V)value etag:(NSString * _Nullable)etag __attribute__((swift_name("init(value:etag:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("Store6_coreKeyEvents")))
@interface SMSStore6_coreKeyEvents : SMSBase
@property (readonly) id<SMSStore6_coreStoreKey> key __attribute__((swift_name("key")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreResultData")))
@interface SMSStore6_coreStoreResultData<V> : SMSBase <SMSStore6_coreStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@property (readonly) BOOL isStale __attribute__((swift_name("isStale")));
@property (readonly) SMSStore6_coreOrigin *origin __attribute__((swift_name("origin")));
@property (readonly) BOOL refreshing __attribute__((swift_name("refreshing")));
@property (readonly) V _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreResultError")))
@interface SMSStore6_coreStoreResultError : SMSBase <SMSStore6_coreStoreResult>
@property (readonly) SMSStore6_coreStoreError *error __attribute__((swift_name("error")));
@property (readonly) BOOL servedStale __attribute__((swift_name("servedStale")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreResultLoading")))
@interface SMSStore6_coreStoreResultLoading : SMSBase <SMSStore6_coreStoreResult>
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreStoreResultRevalidated")))
@interface SMSStore6_coreStoreResultRevalidated : SMSBase <SMSStore6_coreStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@end

__attribute__((swift_name("KotlinIterator")))
@protocol SMSKotlinIterator
@required
- (BOOL)hasNext __attribute__((swift_name("hasNext()")));
- (id _Nullable)next __attribute__((swift_name("next()")));
@end

__attribute__((swift_name("KotlinByteIterator")))
@interface SMSKotlinByteIterator : SMSBase <SMSKotlinIterator>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (SMSByte *)next __attribute__((swift_name("next()")));
- (int8_t)nextByte __attribute__((swift_name("nextByte()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreKeyStatus")))
@interface SMSStore6_coreKeyStatus : SMSBase
@property (readonly) int32_t consecutiveFailures __attribute__((swift_name("consecutiveFailures")));
@property (readonly) BOOL durablyStale __attribute__((swift_name("durablyStale")));
@property (readonly) SMSLong * _Nullable lastFailureAtEpochMillis __attribute__((swift_name("lastFailureAtEpochMillis")));
@property (readonly) SMSLong * _Nullable lastSuccessSequence __attribute__((swift_name("lastSuccessSequence")));
@property (readonly) id<SMSStore6_coreStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
- (instancetype)initWithMeta:(id<SMSStore6_coreStoreMeta> _Nullable)meta lastSuccessSequence:(SMSLong * _Nullable)lastSuccessSequence lastFailureAtEpochMillis:(SMSLong * _Nullable)lastFailureAtEpochMillis consecutiveFailures:(int32_t)consecutiveFailures durablyStale:(BOOL)durablyStale __attribute__((swift_name("init(meta:lastSuccessSequence:lastFailureAtEpochMillis:consecutiveFailures:durablyStale:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreFreshnessContext")))
@interface SMSStore6_coreFreshnessContext : SMSBase
@property (readonly) BOOL epochStale __attribute__((swift_name("epochStale")));
@property (readonly) id<SMSStore6_coreFreshness> freshness __attribute__((swift_name("freshness")));
@property (readonly) BOOL hasResidentValue __attribute__((swift_name("hasResidentValue")));
@property (readonly) id<SMSStore6_coreStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
@property (readonly) int64_t nowEpochMillis __attribute__((swift_name("nowEpochMillis")));
@property (readonly) SMSStore6_coreKeyStatus * _Nullable status __attribute__((swift_name("status")));
- (instancetype)initWithHasResidentValue:(BOOL)hasResidentValue meta:(id<SMSStore6_coreStoreMeta> _Nullable)meta epochStale:(BOOL)epochStale freshness:(id<SMSStore6_coreFreshness>)freshness nowEpochMillis:(int64_t)nowEpochMillis status:(SMSStore6_coreKeyStatus * _Nullable)status __attribute__((swift_name("init(hasResidentValue:meta:epochStale:freshness:nowEpochMillis:status:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Store6_coreOrigin")))
@interface SMSStore6_coreOrigin : SMSKotlinEnum<SMSStore6_coreOrigin *>
@property (class, readonly) SMSStore6_coreOrigin *memory __attribute__((swift_name("memory")));
@property (class, readonly) SMSStore6_coreOrigin *sot __attribute__((swift_name("sot")));
@property (class, readonly) SMSStore6_coreOrigin *fetcher __attribute__((swift_name("fetcher")));
@property (class, readonly) SMSStore6_coreOrigin *overlay __attribute__((swift_name("overlay")));
@property (class, readonly) NSArray<SMSStore6_coreOrigin *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (SMSKotlinArray<SMSStore6_coreOrigin *> *)values __attribute__((swift_name("values()")));
@end

#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
