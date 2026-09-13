#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class Store6Kotlin__SkieSuspendWrappersKt, Store6KotlinUShort, Store6KotlinULong, Store6KotlinUInt, Store6KotlinUByte, Store6KotlinSwiftStoreKey, Store6KotlinSwiftInteropKt, Store6KotlinStoreStateKind, Store6KotlinStoreStateBridge, Store6KotlinStoreRuntimeKt, Store6KotlinStoreResults, Store6KotlinStoreResultRevalidated, Store6KotlinStoreResultLoading, Store6KotlinStoreResultError, Store6KotlinStoreResultData<V>, Store6KotlinStoreNamespace, Store6KotlinStoreException, Store6KotlinStoreErrorPersistence, Store6KotlinStoreErrorMissing, Store6KotlinStoreErrorFreshnessUnsatisfiable, Store6KotlinStoreErrorFetch, Store6KotlinStoreErrorConversion, Store6KotlinStoreErrorConflict, Store6KotlinStoreError, Store6KotlinStoreBuilderKt, Store6KotlinStoreBuilder<K, V>, Store6KotlinSourceAdoption, Store6KotlinSkie_SuspendResultSuccess, Store6KotlinSkie_SuspendResultError, Store6KotlinSkie_SuspendResultCanceled, Store6KotlinSkie_SuspendResult, Store6KotlinSkie_SuspendHandler, Store6KotlinSkie_CancellationHandler, Store6KotlinSkieKotlinStateFlow<T>, Store6KotlinSkieKotlinSharedFlow<T>, Store6KotlinSkieKotlinOptionalStateFlow<T>, Store6KotlinSkieKotlinOptionalSharedFlow<T>, Store6KotlinSkieKotlinOptionalMutableStateFlow<T>, Store6KotlinSkieKotlinOptionalMutableSharedFlow<T>, Store6KotlinSkieKotlinOptionalFlow<T>, Store6KotlinSkieKotlinMutableStateFlow<T>, Store6KotlinSkieKotlinMutableSharedFlow<T>, Store6KotlinSkieKotlinFlow<T>, Store6KotlinSkieColdFlowIterator<E>, Store6KotlinShort, Store6KotlinOrigin, Store6KotlinNumber, Store6KotlinMutableSet<ObjectType>, Store6KotlinMutableDictionary<KeyType, ObjectType>, Store6KotlinLong, Store6KotlinKotlinUnit, Store6KotlinKotlinThrowable, Store6KotlinKotlinRuntimeException, Store6KotlinKotlinIllegalStateException, Store6KotlinKotlinException, Store6KotlinKotlinEnumCompanion, Store6KotlinKotlinEnum<E>, Store6KotlinKotlinCancellationException, Store6KotlinKotlinArray<T>, Store6KotlinKeyStatus, Store6KotlinKeyEventsWritten, Store6KotlinKeyEventsInvalidated, Store6KotlinKeyEventsDeleted, Store6KotlinKeyEvents, Store6KotlinInt, Store6KotlinFreshnessStaleIfError, Store6KotlinFreshnessMustBeFresh, Store6KotlinFreshnessMaxAge, Store6KotlinFreshnessLocalOnly, Store6KotlinFreshnessEvidence, Store6KotlinFreshnessContext, Store6KotlinFreshnessCachedOrFetch, Store6KotlinFloat, Store6KotlinFetcherResultSuccess<V>, Store6KotlinFetcherResultNotModified, Store6KotlinFetcherResultError, Store6KotlinFetcherResultDeleted, Store6KotlinFetchPlanSkip, Store6KotlinFetchPlanFetch, Store6KotlinFetchPlanConditional, Store6KotlinDouble, Store6KotlinByte, Store6KotlinBoolean, Store6KotlinBase, NSString, NSSet<ObjectType>, NSObject, NSNumber, NSMutableSet<ObjectType>, NSMutableDictionary<KeyType, ObjectType>, NSMutableArray<ObjectType>, NSError, NSDictionary<KeyType, ObjectType>, NSArray<ObjectType>;

@protocol Store6KotlinWallClock, Store6KotlinTransactionalSourceOfTruth, Store6KotlinStoreWriteHandle, Store6KotlinStoreTelemetry, Store6KotlinStoreRuntime, Store6KotlinStoreResult, Store6KotlinStoreMeta, Store6KotlinStoreKey, Store6KotlinStore, Store6KotlinSourceOfTruth, Store6KotlinSkie_DispatcherDelegate, Store6KotlinOverlay, Store6KotlinKotlinx_coroutines_coreStateFlow, Store6KotlinKotlinx_coroutines_coreSharedFlow, Store6KotlinKotlinx_coroutines_coreRunnable, Store6KotlinKotlinx_coroutines_coreMutableStateFlow, Store6KotlinKotlinx_coroutines_coreMutableSharedFlow, Store6KotlinKotlinx_coroutines_coreFlowCollector, Store6KotlinKotlinx_coroutines_coreFlow, Store6KotlinKotlinSuspendFunction1, Store6KotlinKotlinSuspendFunction0, Store6KotlinKotlinIterator, Store6KotlinKotlinFunction, Store6KotlinKotlinComparable, Store6KotlinFreshnessValidator, Store6KotlinFreshness, Store6KotlinFetcherResult, Store6KotlinFetcher, Store6KotlinFetchPlan, Store6KotlinBookkeeper, NSCopying;

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
@interface Store6KotlinBase : NSObject
- (instancetype)init __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
+ (void)initialize __attribute__((objc_requires_super));
@end

@interface Store6KotlinBase (Store6KotlinBaseCopying) <NSCopying>
@end

__attribute__((swift_name("KotlinMutableSet")))
@interface Store6KotlinMutableSet<ObjectType> : NSMutableSet<ObjectType>
@end

__attribute__((swift_name("KotlinMutableDictionary")))
@interface Store6KotlinMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType>
@end

@interface NSError (NSErrorStore6KotlinKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

__attribute__((swift_name("KotlinNumber")))
@interface Store6KotlinNumber : NSNumber
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
@interface Store6KotlinByte : Store6KotlinNumber
- (instancetype)initWithChar:(char)value;
+ (instancetype)numberWithChar:(char)value;
@end

__attribute__((swift_name("KotlinUByte")))
@interface Store6KotlinUByte : Store6KotlinNumber
- (instancetype)initWithUnsignedChar:(unsigned char)value;
+ (instancetype)numberWithUnsignedChar:(unsigned char)value;
@end

__attribute__((swift_name("KotlinShort")))
@interface Store6KotlinShort : Store6KotlinNumber
- (instancetype)initWithShort:(short)value;
+ (instancetype)numberWithShort:(short)value;
@end

__attribute__((swift_name("KotlinUShort")))
@interface Store6KotlinUShort : Store6KotlinNumber
- (instancetype)initWithUnsignedShort:(unsigned short)value;
+ (instancetype)numberWithUnsignedShort:(unsigned short)value;
@end

__attribute__((swift_name("KotlinInt")))
@interface Store6KotlinInt : Store6KotlinNumber
- (instancetype)initWithInt:(int)value;
+ (instancetype)numberWithInt:(int)value;
@end

__attribute__((swift_name("KotlinUInt")))
@interface Store6KotlinUInt : Store6KotlinNumber
- (instancetype)initWithUnsignedInt:(unsigned int)value;
+ (instancetype)numberWithUnsignedInt:(unsigned int)value;
@end

__attribute__((swift_name("KotlinLong")))
@interface Store6KotlinLong : Store6KotlinNumber
- (instancetype)initWithLongLong:(long long)value;
+ (instancetype)numberWithLongLong:(long long)value;
@end

__attribute__((swift_name("KotlinULong")))
@interface Store6KotlinULong : Store6KotlinNumber
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value;
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value;
@end

__attribute__((swift_name("KotlinFloat")))
@interface Store6KotlinFloat : Store6KotlinNumber
- (instancetype)initWithFloat:(float)value;
+ (instancetype)numberWithFloat:(float)value;
@end

__attribute__((swift_name("KotlinDouble")))
@interface Store6KotlinDouble : Store6KotlinNumber
- (instancetype)initWithDouble:(double)value;
+ (instancetype)numberWithDouble:(double)value;
@end

__attribute__((swift_name("KotlinBoolean")))
@interface Store6KotlinBoolean : Store6KotlinNumber
- (instancetype)initWithBool:(BOOL)value;
+ (instancetype)numberWithBool:(BOOL)value;
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieColdFlowIterator")))
@interface Store6KotlinSkieColdFlowIterator<E> : Store6KotlinBase
- (instancetype)initWithFlow:(id<Store6KotlinKotlinx_coroutines_coreFlow>)flow __attribute__((swift_name("init(flow:)"))) __attribute__((objc_designated_initializer));
- (void)cancel __attribute__((swift_name("cancel()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)hasNextWithCompletionHandler:(void (^)(Store6KotlinBoolean * _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("hasNext(completionHandler:)")));
- (E _Nullable)next __attribute__((swift_name("next()")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlow")))
@protocol Store6KotlinKotlinx_coroutines_coreFlow
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinFlow")))
@interface Store6KotlinSkieKotlinFlow<__covariant T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreFlow>
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreSharedFlow")))
@protocol Store6KotlinKotlinx_coroutines_coreSharedFlow <Store6KotlinKotlinx_coroutines_coreFlow>
@required
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlowCollector")))
@protocol Store6KotlinKotlinx_coroutines_coreFlowCollector
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(id _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreMutableSharedFlow")))
@protocol Store6KotlinKotlinx_coroutines_coreMutableSharedFlow <Store6KotlinKotlinx_coroutines_coreSharedFlow, Store6KotlinKotlinx_coroutines_coreFlowCollector>
@required

/**
 * @note annotations
 *   kotlinx.coroutines.ExperimentalCoroutinesApi
*/
- (void)resetReplayCache __attribute__((swift_name("resetReplayCache()")));
- (BOOL)tryEmitValue:(id _Nullable)value __attribute__((swift_name("tryEmit(value:)")));
@property (readonly) id<Store6KotlinKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinMutableSharedFlow")))
@interface Store6KotlinSkieKotlinMutableSharedFlow<T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreMutableSharedFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<Store6KotlinKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreMutableSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));

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
@protocol Store6KotlinKotlinx_coroutines_coreStateFlow <Store6KotlinKotlinx_coroutines_coreSharedFlow>
@required
@property (readonly) id _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreMutableStateFlow")))
@protocol Store6KotlinKotlinx_coroutines_coreMutableStateFlow <Store6KotlinKotlinx_coroutines_coreStateFlow, Store6KotlinKotlinx_coroutines_coreMutableSharedFlow>
@required
- (void)setValue:(id _Nullable)value __attribute__((swift_name("setValue(_:)")));
- (BOOL)compareAndSetExpect:(id _Nullable)expect update:(id _Nullable)update __attribute__((swift_name("compareAndSet(expect:update:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinMutableStateFlow")))
@interface Store6KotlinSkieKotlinMutableStateFlow<T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreMutableStateFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<Store6KotlinKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@property T value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreMutableStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
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
@interface Store6KotlinSkieKotlinOptionalFlow<__covariant T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreFlow>
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalMutableSharedFlow")))
@interface Store6KotlinSkieKotlinOptionalMutableSharedFlow<T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreMutableSharedFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<Store6KotlinKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreMutableSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));

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
@interface Store6KotlinSkieKotlinOptionalMutableStateFlow<T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreMutableStateFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) id<Store6KotlinKotlinx_coroutines_coreStateFlow> subscriptionCount __attribute__((swift_name("subscriptionCount")));
@property T _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreMutableStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
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
@interface Store6KotlinSkieKotlinOptionalSharedFlow<__covariant T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreSharedFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinOptionalStateFlow")))
@interface Store6KotlinSkieKotlinOptionalStateFlow<__covariant T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreStateFlow>
@property (readonly) NSArray<id> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) T _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinSharedFlow")))
@interface Store6KotlinSkieKotlinSharedFlow<__covariant T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreSharedFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreSharedFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SkieKotlinStateFlow")))
@interface Store6KotlinSkieKotlinStateFlow<__covariant T> : Store6KotlinBase <Store6KotlinKotlinx_coroutines_coreStateFlow>
@property (readonly) NSArray<T> *replayCache __attribute__((swift_name("replayCache")));
@property (readonly) T value __attribute__((swift_name("value")));
- (instancetype)initWithDelegate:(id<Store6KotlinKotlinx_coroutines_coreStateFlow>)delegate __attribute__((swift_name("init(_:)"))) __attribute__((objc_designated_initializer));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_CancellationHandler")))
@interface Store6KotlinSkie_CancellationHandler : Store6KotlinBase
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (void)cancel __attribute__((swift_name("cancel()")));
@end

__attribute__((swift_name("Skie_DispatcherDelegate")))
@protocol Store6KotlinSkie_DispatcherDelegate
@required
- (void)dispatchBlock:(id<Store6KotlinKotlinx_coroutines_coreRunnable>)block __attribute__((swift_name("dispatch(block:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendHandler")))
@interface Store6KotlinSkie_SuspendHandler : Store6KotlinBase
- (instancetype)initWithCancellationHandler:(Store6KotlinSkie_CancellationHandler *)cancellationHandler dispatcherDelegate:(id<Store6KotlinSkie_DispatcherDelegate>)dispatcherDelegate onResult:(void (^)(Store6KotlinSkie_SuspendResult *))onResult __attribute__((swift_name("init(cancellationHandler:dispatcherDelegate:onResult:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Skie_SuspendResult")))
@interface Store6KotlinSkie_SuspendResult : Store6KotlinBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Canceled")))
@interface Store6KotlinSkie_SuspendResultCanceled : Store6KotlinSkie_SuspendResult
@property (class, readonly, getter=shared) Store6KotlinSkie_SuspendResultCanceled *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)canceled __attribute__((swift_name("init()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Error")))
@interface Store6KotlinSkie_SuspendResultError : Store6KotlinSkie_SuspendResult
@property (readonly) NSError *error __attribute__((swift_name("error")));
- (instancetype)initWithError:(NSError *)error __attribute__((swift_name("init(error:)"))) __attribute__((objc_designated_initializer));
- (Store6KotlinSkie_SuspendResultError *)doCopyError:(NSError *)error __attribute__((swift_name("doCopy(error:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Skie_SuspendResult.Success")))
@interface Store6KotlinSkie_SuspendResultSuccess : Store6KotlinSkie_SuspendResult
@property (readonly) id _Nullable value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(id _Nullable)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (Store6KotlinSkie_SuspendResultSuccess *)doCopyValue:(id _Nullable)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("Freshness")))
@protocol Store6KotlinFreshness
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessCachedOrFetch")))
@interface Store6KotlinFreshnessCachedOrFetch : Store6KotlinBase <Store6KotlinFreshness>
@property (class, readonly, getter=shared) Store6KotlinFreshnessCachedOrFetch *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)cachedOrFetch __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessLocalOnly")))
@interface Store6KotlinFreshnessLocalOnly : Store6KotlinBase <Store6KotlinFreshness>
@property (class, readonly, getter=shared) Store6KotlinFreshnessLocalOnly *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)localOnly __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessMaxAge")))
@interface Store6KotlinFreshnessMaxAge : Store6KotlinBase <Store6KotlinFreshness>
@property (readonly) int64_t notOlderThan __attribute__((swift_name("notOlderThan")));
- (instancetype)initWithNotOlderThan:(int64_t)notOlderThan __attribute__((swift_name("init(notOlderThan:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessMustBeFresh")))
@interface Store6KotlinFreshnessMustBeFresh : Store6KotlinBase <Store6KotlinFreshness>
@property (class, readonly, getter=shared) Store6KotlinFreshnessMustBeFresh *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)mustBeFresh __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessStaleIfError")))
@interface Store6KotlinFreshnessStaleIfError : Store6KotlinBase <Store6KotlinFreshness>
@property (class, readonly, getter=shared) Store6KotlinFreshnessStaleIfError *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)staleIfError __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("KotlinComparable")))
@protocol Store6KotlinKotlinComparable
@required
- (int32_t)compareToOther:(id _Nullable)other __attribute__((swift_name("compareTo(other:)")));
@end

__attribute__((swift_name("KotlinEnum")))
@interface Store6KotlinKotlinEnum<E> : Store6KotlinBase <Store6KotlinKotlinComparable>
@property (class, readonly, getter=companion) Store6KotlinKotlinEnumCompanion *companion __attribute__((swift_name("companion")));
@property (readonly) NSString *name __attribute__((swift_name("name")));
@property (readonly) int32_t ordinal __attribute__((swift_name("ordinal")));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer));
- (int32_t)compareToOther:(E)other __attribute__((swift_name("compareTo(other:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Origin")))
@interface Store6KotlinOrigin : Store6KotlinKotlinEnum<Store6KotlinOrigin *>
@property (class, readonly) Store6KotlinOrigin *memory __attribute__((swift_name("memory")));
@property (class, readonly) Store6KotlinOrigin *sot __attribute__((swift_name("sot")));
@property (class, readonly) Store6KotlinOrigin *fetcher __attribute__((swift_name("fetcher")));
@property (class, readonly) Store6KotlinOrigin *overlay __attribute__((swift_name("overlay")));
@property (class, readonly) NSArray<Store6KotlinOrigin *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (Store6KotlinKotlinArray<Store6KotlinOrigin *> *)values __attribute__((swift_name("values()")));
@end


/**
 * @note annotations
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store")))
@protocol Store6KotlinStore
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearKey:(id<Store6KotlinStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clear(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearNamespaceNamespace:(Store6KotlinStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearNamespace(namespace:completionHandler:)")));
- (void)close __attribute__((swift_name("close()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)getKey:(id<Store6KotlinStoreKey>)key freshness:(id<Store6KotlinFreshness>)freshness completionHandler:(void (^)(id _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("get(key:freshness:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateKey:(id<Store6KotlinStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidate(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateNamespaceNamespace:(Store6KotlinStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateNamespace(namespace:completionHandler:)")));
- (id<Store6KotlinKotlinx_coroutines_coreFlow>)streamKey:(id<Store6KotlinStoreKey>)key freshness:(id<Store6KotlinFreshness>)freshness __attribute__((swift_name("stream(key:freshness:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreBuilder")))
@interface Store6KotlinStoreBuilder<K, V> : Store6KotlinBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)bookkeeperBookkeeper:(id<Store6KotlinBookkeeper>)bookkeeper __attribute__((swift_name("bookkeeper(bookkeeper:)")));
- (void)fetcherFetch:(id<Store6KotlinKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcher(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherFetcher:(id<Store6KotlinFetcher>)fetcher __attribute__((swift_name("fetcher(fetcher:)")));
- (void)fetcherOfResultFetch:(id<Store6KotlinKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcherOfResult(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)freshnessValidatorValidator:(id<Store6KotlinFreshnessValidator>)validator __attribute__((swift_name("freshnessValidator(validator:)")));
- (void)maxIdleKeysCount:(int32_t)count __attribute__((swift_name("maxIdleKeys(count:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)overlayOverlay:(id<Store6KotlinOverlay>)overlay __attribute__((swift_name("overlay(overlay:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)persistenceSot:(id<Store6KotlinSourceOfTruth>)sot __attribute__((swift_name("persistence(sot:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)telemetryTelemetry:(id<Store6KotlinStoreTelemetry>)telemetry __attribute__((swift_name("telemetry(telemetry:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)wallClockWallClock:(id<Store6KotlinWallClock>)wallClock __attribute__((swift_name("wallClock(wallClock:)")));
@end

__attribute__((swift_name("StoreError")))
@interface Store6KotlinStoreError : Store6KotlinBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Conflict")))
@interface Store6KotlinStoreErrorConflict : Store6KotlinStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) id<Store6KotlinStoreMeta> _Nullable serverMeta __attribute__((swift_name("serverMeta")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Conversion")))
@interface Store6KotlinStoreErrorConversion : Store6KotlinStoreError
@property (readonly) Store6KotlinKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Fetch")))
@interface Store6KotlinStoreErrorFetch : Store6KotlinStoreError
@property (readonly) Store6KotlinKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.FreshnessUnsatisfiable")))
@interface Store6KotlinStoreErrorFreshnessUnsatisfiable : Store6KotlinStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Missing")))
@interface Store6KotlinStoreErrorMissing : Store6KotlinStoreError
@property (readonly) id<Store6KotlinStoreKey> key __attribute__((swift_name("key")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Persistence")))
@interface Store6KotlinStoreErrorPersistence : Store6KotlinStoreError
@property (readonly) Store6KotlinKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((swift_name("KotlinThrowable")))
@interface Store6KotlinKotlinThrowable : Store6KotlinBase
@property (readonly) Store6KotlinKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString * _Nullable message __attribute__((swift_name("message")));
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   kotlin.experimental.ExperimentalNativeApi
*/
- (Store6KotlinKotlinArray<NSString *> *)getStackTrace __attribute__((swift_name("getStackTrace()")));
- (void)printStackTrace __attribute__((swift_name("printStackTrace()")));
- (NSString *)description __attribute__((swift_name("description()")));
- (NSError *)asError __attribute__((swift_name("asError()")));
@end

__attribute__((swift_name("KotlinException")))
@interface Store6KotlinKotlinException : Store6KotlinKotlinThrowable
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinRuntimeException")))
@interface Store6KotlinKotlinRuntimeException : Store6KotlinKotlinException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreException")))
@interface Store6KotlinStoreException : Store6KotlinKotlinRuntimeException
@property (readonly) Store6KotlinStoreError *error __attribute__((swift_name("error")));
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
- (instancetype)initWithCause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@end

__attribute__((swift_name("StoreKey")))
@protocol Store6KotlinStoreKey
@required
- (NSString *)canonicalId __attribute__((swift_name("canonicalId()")));
@property (readonly, getter=namespace) Store6KotlinStoreNamespace *namespace_ __attribute__((swift_name("namespace_")));
@end

__attribute__((swift_name("StoreMeta")))
@protocol Store6KotlinStoreMeta
@required
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) int64_t writtenAtEpochMillis __attribute__((swift_name("writtenAtEpochMillis")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreNamespace")))
@interface Store6KotlinStoreNamespace : Store6KotlinBase
@property (readonly) NSString *value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("StoreResult")))
@protocol Store6KotlinStoreResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultData")))
@interface Store6KotlinStoreResultData<V> : Store6KotlinBase <Store6KotlinStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@property (readonly) BOOL isStale __attribute__((swift_name("isStale")));
@property (readonly) Store6KotlinOrigin *origin __attribute__((swift_name("origin")));
@property (readonly) BOOL refreshing __attribute__((swift_name("refreshing")));
@property (readonly) V _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultError")))
@interface Store6KotlinStoreResultError : Store6KotlinBase <Store6KotlinStoreResult>
@property (readonly) Store6KotlinStoreError *error __attribute__((swift_name("error")));
@property (readonly) BOOL servedStale __attribute__((swift_name("servedStale")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultLoading")))
@interface Store6KotlinStoreResultLoading : Store6KotlinBase <Store6KotlinStoreResult>
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultRevalidated")))
@interface Store6KotlinStoreResultRevalidated : Store6KotlinBase <Store6KotlinStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Bookkeeper")))
@protocol Store6KotlinBookkeeper
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
- (void)advanceStaleWatermarkNamespace:(Store6KotlinStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("advanceStaleWatermark(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetKey:(id<Store6KotlinStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forget(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetNamespaceNamespace:(Store6KotlinStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetNamespace(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)markStaleKey:(id<Store6KotlinStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("markStale(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordFailureKey:(id<Store6KotlinStoreKey>)key atEpochMillis:(int64_t)atEpochMillis completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordFailure(key:atEpochMillis:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordSuccessKey:(id<Store6KotlinStoreKey>)key meta:(id<Store6KotlinStoreMeta>)meta completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordSuccess(key:meta:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)statusKey:(id<Store6KotlinStoreKey>)key completionHandler:(void (^)(Store6KotlinKeyStatus * _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("status(key:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("FetchPlan")))
@protocol Store6KotlinFetchPlan
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetchPlanConditional")))
@interface Store6KotlinFetchPlanConditional : Store6KotlinBase <Store6KotlinFetchPlan>
@property (readonly) NSString *etag __attribute__((swift_name("etag")));
@property (readonly) BOOL servesResidentWhileFetching __attribute__((swift_name("servesResidentWhileFetching")));
- (instancetype)initWithEtag:(NSString *)etag servesResidentWhileFetching:(BOOL)servesResidentWhileFetching __attribute__((swift_name("init(etag:servesResidentWhileFetching:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetchPlanFetch")))
@interface Store6KotlinFetchPlanFetch : Store6KotlinBase <Store6KotlinFetchPlan>
@property (readonly) BOOL servesResidentWhileFetching __attribute__((swift_name("servesResidentWhileFetching")));
- (instancetype)initWithServesResidentWhileFetching:(BOOL)servesResidentWhileFetching __attribute__((swift_name("init(servesResidentWhileFetching:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetchPlanSkip")))
@interface Store6KotlinFetchPlanSkip : Store6KotlinBase <Store6KotlinFetchPlan>
@property (class, readonly, getter=shared) Store6KotlinFetchPlanSkip *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)skip __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Fetcher")))
@protocol Store6KotlinFetcher
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)fetchKey:(id<Store6KotlinStoreKey>)key etag:(NSString * _Nullable)etag completionHandler:(void (^)(id<Store6KotlinFetcherResult> _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("fetch(key:etag:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("FetcherResult")))
@protocol Store6KotlinFetcherResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultDeleted")))
@interface Store6KotlinFetcherResultDeleted : Store6KotlinBase <Store6KotlinFetcherResult>
@property (class, readonly, getter=shared) Store6KotlinFetcherResultDeleted *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)deleted __attribute__((swift_name("init()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultError")))
@interface Store6KotlinFetcherResultError : Store6KotlinBase <Store6KotlinFetcherResult>
@property (readonly) Store6KotlinKotlinThrowable *cause __attribute__((swift_name("cause")));
- (instancetype)initWithCause:(Store6KotlinKotlinThrowable *)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultNotModified")))
@interface Store6KotlinFetcherResultNotModified : Store6KotlinBase <Store6KotlinFetcherResult>
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
- (instancetype)initWithEtag:(NSString * _Nullable)etag __attribute__((swift_name("init(etag:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultSuccess")))
@interface Store6KotlinFetcherResultSuccess<V> : Store6KotlinBase <Store6KotlinFetcherResult>
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) V value __attribute__((swift_name("value")));
- (instancetype)initWithValue:(V)value etag:(NSString * _Nullable)etag __attribute__((swift_name("init(value:etag:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessContext")))
@interface Store6KotlinFreshnessContext : Store6KotlinBase
@property (readonly) BOOL epochStale __attribute__((swift_name("epochStale")));
@property (readonly) id<Store6KotlinFreshness> freshness __attribute__((swift_name("freshness")));
@property (readonly) BOOL hasResidentValue __attribute__((swift_name("hasResidentValue")));
@property (readonly) id<Store6KotlinStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
@property (readonly) int64_t nowEpochMillis __attribute__((swift_name("nowEpochMillis")));
@property (readonly) Store6KotlinKeyStatus * _Nullable status __attribute__((swift_name("status")));
- (instancetype)initWithHasResidentValue:(BOOL)hasResidentValue meta:(id<Store6KotlinStoreMeta> _Nullable)meta epochStale:(BOOL)epochStale freshness:(id<Store6KotlinFreshness>)freshness nowEpochMillis:(int64_t)nowEpochMillis status:(Store6KotlinKeyStatus * _Nullable)status __attribute__((swift_name("init(hasResidentValue:meta:epochStale:freshness:nowEpochMillis:status:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessEvidence")))
@interface Store6KotlinFreshnessEvidence : Store6KotlinBase
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("FreshnessValidator")))
@protocol Store6KotlinFreshnessValidator
@required
- (id<Store6KotlinFetchPlan>)planContext:(Store6KotlinFreshnessContext *)context __attribute__((swift_name("plan(context:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("KeyEvents")))
@interface Store6KotlinKeyEvents : Store6KotlinBase
@property (readonly) id<Store6KotlinStoreKey> key __attribute__((swift_name("key")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyEvents.Deleted")))
@interface Store6KotlinKeyEventsDeleted : Store6KotlinKeyEvents
@property (readonly) id<Store6KotlinStoreKey> key __attribute__((swift_name("key")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyEvents.Invalidated")))
@interface Store6KotlinKeyEventsInvalidated : Store6KotlinKeyEvents
@property (readonly) id<Store6KotlinStoreKey> key __attribute__((swift_name("key")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyEvents.Written")))
@interface Store6KotlinKeyEventsWritten : Store6KotlinKeyEvents
@property (readonly) id<Store6KotlinStoreKey> key __attribute__((swift_name("key")));
@property (readonly) Store6KotlinOrigin *origin __attribute__((swift_name("origin")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyStatus")))
@interface Store6KotlinKeyStatus : Store6KotlinBase
@property (readonly) int32_t consecutiveFailures __attribute__((swift_name("consecutiveFailures")));
@property (readonly) BOOL durablyStale __attribute__((swift_name("durablyStale")));
@property (readonly) Store6KotlinLong * _Nullable lastFailureAtEpochMillis __attribute__((swift_name("lastFailureAtEpochMillis")));
@property (readonly) Store6KotlinLong * _Nullable lastSuccessSequence __attribute__((swift_name("lastSuccessSequence")));
@property (readonly) id<Store6KotlinStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
- (instancetype)initWithMeta:(id<Store6KotlinStoreMeta> _Nullable)meta lastSuccessSequence:(Store6KotlinLong * _Nullable)lastSuccessSequence lastFailureAtEpochMillis:(Store6KotlinLong * _Nullable)lastFailureAtEpochMillis consecutiveFailures:(int32_t)consecutiveFailures durablyStale:(BOOL)durablyStale __attribute__((swift_name("init(meta:lastSuccessSequence:lastFailureAtEpochMillis:consecutiveFailures:durablyStale:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Overlay")))
@protocol Store6KotlinOverlay
@required
- (id _Nullable)applyKey:(id<Store6KotlinStoreKey>)key base:(id _Nullable)base __attribute__((swift_name("apply(key:base:)")));
- (id _Nullable)applyKey:(id<Store6KotlinStoreKey>)key base:(id _Nullable)base adoption:(Store6KotlinSourceAdoption * _Nullable)adoption __attribute__((swift_name("apply(key:base:adoption:)")));
@property (readonly) id<Store6KotlinKotlinx_coroutines_coreFlow> changes __attribute__((swift_name("changes")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SourceAdoption")))
@interface Store6KotlinSourceAdoption : Store6KotlinBase
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("SourceOfTruth")))
@protocol Store6KotlinSourceOfTruth
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteKey:(id<Store6KotlinStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("delete(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteNamespaceNamespace:(Store6KotlinStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteNamespace(namespace:completionHandler:)")));
- (id<Store6KotlinKotlinx_coroutines_coreFlow>)readerKey:(id<Store6KotlinStoreKey>)key __attribute__((swift_name("reader(key:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)writeKey:(id<Store6KotlinStoreKey>)key value:(id)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("write(key:value:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResults")))
@interface Store6KotlinStoreResults : Store6KotlinBase
@property (class, readonly, getter=shared) Store6KotlinStoreResults *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)storeResults __attribute__((swift_name("init()")));
- (Store6KotlinStoreErrorConflict *)conflictServerMeta:(id<Store6KotlinStoreMeta> _Nullable)serverMeta message:(NSString *)message __attribute__((swift_name("conflict(serverMeta:message:)")));
- (Store6KotlinStoreErrorConversion *)conversionErrorMessage:(NSString *)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("conversionError(message:cause:)")));
- (Store6KotlinStoreResultData<id> *)dataValue:(id _Nullable)value origin:(Store6KotlinOrigin *)origin age:(int64_t)age isStale:(BOOL)isStale refreshing:(BOOL)refreshing __attribute__((swift_name("data(value:origin:age:isStale:refreshing:)")));
- (Store6KotlinStoreResultError *)errorError:(Store6KotlinStoreError *)error servedStale:(BOOL)servedStale __attribute__((swift_name("error(error:servedStale:)")));
- (Store6KotlinStoreException *)exceptionError:(Store6KotlinStoreError *)error cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("exception(error:cause:)")));
- (Store6KotlinStoreErrorFetch *)fetchErrorMessage:(NSString *)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("fetchError(message:cause:)")));
- (Store6KotlinStoreErrorFreshnessUnsatisfiable *)freshnessUnsatisfiableMessage:(NSString *)message __attribute__((swift_name("freshnessUnsatisfiable(message:)")));
- (Store6KotlinStoreResultLoading *)loading __attribute__((swift_name("loading()")));
- (Store6KotlinStoreErrorMissing *)missingKey:(id<Store6KotlinStoreKey>)key message:(NSString *)message __attribute__((swift_name("missing(key:message:)")));
- (Store6KotlinStoreErrorPersistence *)persistenceErrorMessage:(NSString *)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("persistenceError(message:cause:)")));
- (Store6KotlinStoreResultRevalidated *)revalidatedAge:(int64_t)age __attribute__((swift_name("revalidated(age:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("StoreRuntime")))
@protocol Store6KotlinStoreRuntime
@required
@property (readonly) id<Store6KotlinKotlinx_coroutines_coreFlow> keyEvents __attribute__((swift_name("keyEvents")));
@property (readonly) id<Store6KotlinStoreTelemetry> _Nullable telemetry __attribute__((swift_name("telemetry")));
@property (readonly) id<Store6KotlinStoreWriteHandle> writeHandle __attribute__((swift_name("writeHandle")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("StoreTelemetry")))
@protocol Store6KotlinStoreTelemetry
@required
- (void)onClearedKey:(id<Store6KotlinStoreKey>)key __attribute__((swift_name("onCleared(key:)")));
- (void)onFetchFailedKey:(id<Store6KotlinStoreKey>)key error:(Store6KotlinStoreError *)error duration:(int64_t)duration __attribute__((swift_name("onFetchFailed(key:error:duration:)")));
- (void)onFetchStartedKey:(id<Store6KotlinStoreKey>)key __attribute__((swift_name("onFetchStarted(key:)")));
- (void)onFetchSucceededKey:(id<Store6KotlinStoreKey>)key duration:(int64_t)duration __attribute__((swift_name("onFetchSucceeded(key:duration:)")));
- (void)onInvalidatedKey:(id<Store6KotlinStoreKey>)key __attribute__((swift_name("onInvalidated(key:)")));
- (void)onServeKey:(id<Store6KotlinStoreKey>)key origin:(Store6KotlinOrigin *)origin __attribute__((swift_name("onServe(key:origin:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("StoreWriteHandle")))
@protocol Store6KotlinStoreWriteHandle
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)applyKey:(id<Store6KotlinStoreKey>)key value:(id)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("apply(key:value:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)applyAcknowledgementKey:(id<Store6KotlinStoreKey>)key value:(id)value etag:(NSString * _Nullable)etag freshnessEvidence:(Store6KotlinFreshnessEvidence * _Nullable)freshnessEvidence adoption:(Store6KotlinSourceAdoption * _Nullable)adoption completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("applyAcknowledgement(key:value:etag:freshnessEvidence:adoption:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)captureFreshnessKey:(id<Store6KotlinStoreKey>)key completionHandler:(void (^)(Store6KotlinFreshnessEvidence * _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("captureFreshness(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)confirmFreshKey:(id<Store6KotlinStoreKey>)key etag:(NSString * _Nullable)etag completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("confirmFresh(key:etag:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)markStaleKey:(id<Store6KotlinStoreKey>)key completionHandler_:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("markStale(key:completionHandler_:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("TransactionalSourceOfTruth")))
@protocol Store6KotlinTransactionalSourceOfTruth <Store6KotlinSourceOfTruth>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)withTransactionBlock:(id<Store6KotlinKotlinSuspendFunction0>)block completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("withTransaction(block:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("WallClock")))
@protocol Store6KotlinWallClock
@required
- (int64_t)nowEpochMillis __attribute__((swift_name("nowEpochMillis()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreStateBridge")))
@interface Store6KotlinStoreStateBridge : Store6KotlinBase
@property (readonly) int64_t ageMillis __attribute__((swift_name("ageMillis")));
@property (readonly) Store6KotlinStoreError * _Nullable error __attribute__((swift_name("error")));
@property (readonly) BOOL isStale __attribute__((swift_name("isStale")));
@property (readonly) Store6KotlinStoreStateKind *kind __attribute__((swift_name("kind")));
@property (readonly) Store6KotlinOrigin * _Nullable origin __attribute__((swift_name("origin")));
@property (readonly) BOOL refreshing __attribute__((swift_name("refreshing")));
@property (readonly) BOOL servedStale __attribute__((swift_name("servedStale")));
@property (readonly) id _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreStateKind")))
@interface Store6KotlinStoreStateKind : Store6KotlinKotlinEnum<Store6KotlinStoreStateKind *>
@property (class, readonly) Store6KotlinStoreStateKind *loading __attribute__((swift_name("loading")));
@property (class, readonly) Store6KotlinStoreStateKind *data __attribute__((swift_name("data")));
@property (class, readonly) Store6KotlinStoreStateKind *revalidated __attribute__((swift_name("revalidated")));
@property (class, readonly) Store6KotlinStoreStateKind *error __attribute__((swift_name("error")));
@property (class, readonly) NSArray<Store6KotlinStoreStateKind *> *entries __attribute__((swift_name("entries")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (Store6KotlinKotlinArray<Store6KotlinStoreStateKind *> *)values __attribute__((swift_name("values()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SwiftStoreKey")))
@interface Store6KotlinSwiftStoreKey : Store6KotlinBase <Store6KotlinStoreKey>
@property (readonly, getter=namespace) Store6KotlinStoreNamespace *namespace_ __attribute__((swift_name("namespace_")));
- (instancetype)initWithNamespace:(NSString *)namespace_ id:(NSString *)id __attribute__((swift_name("init(namespace:id:)"))) __attribute__((objc_designated_initializer));
- (NSString *)canonicalId __attribute__((swift_name("canonicalId()")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreBuilderKt")))
@interface Store6KotlinStoreBuilderKt : Store6KotlinBase
+ (id<Store6KotlinStore>)storeConfigure:(void (^)(Store6KotlinStoreBuilder<id<Store6KotlinStoreKey>, id> *))configure __attribute__((swift_name("store(configure:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreRuntimeKt")))
@interface Store6KotlinStoreRuntimeKt : Store6KotlinBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
+ (id<Store6KotlinStoreRuntime> _Nullable)runtime:(id<Store6KotlinStore>)receiver __attribute__((swift_name("runtime(_:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SwiftInteropKt")))
@interface Store6KotlinSwiftInteropKt : Store6KotlinBase
+ (id<Store6KotlinFreshness>)maxAgeFreshnessNotOlderThanMillis:(int64_t)notOlderThanMillis __attribute__((swift_name("maxAgeFreshness(notOlderThanMillis:)")));

/**
 * @note This method converts instances of StoreException, CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
+ (void)storeGetStore:(id<Store6KotlinStore>)store key:(id<Store6KotlinStoreKey>)key freshness:(id<Store6KotlinFreshness>)freshness completionHandler:(void (^)(id _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("storeGet(store:key:freshness:completionHandler:)")));
+ (id<Store6KotlinKotlinx_coroutines_coreFlow>)storeStatesStore:(id<Store6KotlinStore>)store key:(id<Store6KotlinStoreKey>)key freshness:(id<Store6KotlinFreshness>)freshness __attribute__((swift_name("storeStates(store:key:freshness:)")));
+ (id<Store6KotlinStore>)swiftStoreFetch:(void (^)(id<Store6KotlinStoreKey>, Store6KotlinKotlinUnit *(^)(id _Nullable, NSString * _Nullable)))fetch __attribute__((swift_name("swiftStore(fetch:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("__SkieSuspendWrappersKt")))
@interface Store6Kotlin__SkieSuspendWrappersKt : Store6KotlinBase
+ (void)Skie_Suspend__0__storeGetStore:(id<Store6KotlinStore>)store key:(id<Store6KotlinStoreKey>)key freshness:(id<Store6KotlinFreshness>)freshness suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__0__storeGet(store:key:freshness:suspendHandler:)")));
+ (void)Skie_Suspend__10__deleteDispatchReceiver:(id<Store6KotlinSourceOfTruth>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__10__delete(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__11__deleteAllDispatchReceiver:(id<Store6KotlinSourceOfTruth>)dispatchReceiver suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__11__deleteAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__12__deleteNamespaceDispatchReceiver:(id<Store6KotlinSourceOfTruth>)dispatchReceiver namespace:(Store6KotlinStoreNamespace *)namespace_ suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__12__deleteNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__13__writeDispatchReceiver:(id<Store6KotlinSourceOfTruth>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key value:(id)value suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__13__write(dispatchReceiver:key:value:suspendHandler:)")));
+ (void)Skie_Suspend__14__invokeDispatchReceiver:(id<Store6KotlinKotlinSuspendFunction1>)dispatchReceiver p1:(id _Nullable)p1 suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__14__invoke(dispatchReceiver:p1:suspendHandler:)")));
+ (void)Skie_Suspend__15__fetchDispatchReceiver:(id<Store6KotlinFetcher>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key etag:(NSString * _Nullable)etag suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__15__fetch(dispatchReceiver:key:etag:suspendHandler:)")));
+ (void)Skie_Suspend__16__advanceGlobalStaleWatermarkDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__16__advanceGlobalStaleWatermark(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__17__advanceStaleWatermarkDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver namespace:(Store6KotlinStoreNamespace *)namespace_ suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__17__advanceStaleWatermark(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__18__forgetDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__18__forget(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__19__forgetAllDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__19__forgetAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__1__clearDispatchReceiver:(id<Store6KotlinStore>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__1__clear(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__20__forgetNamespaceDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver namespace:(Store6KotlinStoreNamespace *)namespace_ suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__20__forgetNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__21__markStaleDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__21__markStale(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__22__recordFailureDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key atEpochMillis:(int64_t)atEpochMillis suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__22__recordFailure(dispatchReceiver:key:atEpochMillis:suspendHandler:)")));
+ (void)Skie_Suspend__23__recordSuccessDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key meta:(id<Store6KotlinStoreMeta>)meta suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__23__recordSuccess(dispatchReceiver:key:meta:suspendHandler:)")));
+ (void)Skie_Suspend__24__statusDispatchReceiver:(id<Store6KotlinBookkeeper>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__24__status(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__25__applyDispatchReceiver:(id<Store6KotlinStoreWriteHandle>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key value:(id)value suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__25__apply(dispatchReceiver:key:value:suspendHandler:)")));
+ (void)Skie_Suspend__26__applyAcknowledgementDispatchReceiver:(id<Store6KotlinStoreWriteHandle>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key value:(id)value etag:(NSString * _Nullable)etag freshnessEvidence:(Store6KotlinFreshnessEvidence * _Nullable)freshnessEvidence adoption:(Store6KotlinSourceAdoption * _Nullable)adoption suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__26__applyAcknowledgement(dispatchReceiver:key:value:etag:freshnessEvidence:adoption:suspendHandler:)")));
+ (void)Skie_Suspend__27__captureFreshnessDispatchReceiver:(id<Store6KotlinStoreWriteHandle>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__27__captureFreshness(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__28__confirmFreshDispatchReceiver:(id<Store6KotlinStoreWriteHandle>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key etag:(NSString * _Nullable)etag suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__28__confirmFresh(dispatchReceiver:key:etag:suspendHandler:)")));
+ (void)Skie_Suspend__29__markStaleDispatchReceiver:(id<Store6KotlinStoreWriteHandle>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__29__markStale(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__2__clearAllDispatchReceiver:(id<Store6KotlinStore>)dispatchReceiver suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__2__clearAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__30__withTransactionDispatchReceiver:(id<Store6KotlinTransactionalSourceOfTruth>)dispatchReceiver block:(id<Store6KotlinKotlinSuspendFunction0>)block suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__30__withTransaction(dispatchReceiver:block:suspendHandler:)")));
+ (void)Skie_Suspend__31__invokeDispatchReceiver:(id<Store6KotlinKotlinSuspendFunction0>)dispatchReceiver suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__31__invoke(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__32__hasNextDispatchReceiver:(Store6KotlinSkieColdFlowIterator<id> *)dispatchReceiver suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__32__hasNext(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__3__clearNamespaceDispatchReceiver:(id<Store6KotlinStore>)dispatchReceiver namespace:(Store6KotlinStoreNamespace *)namespace_ suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__3__clearNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__4__getDispatchReceiver:(id<Store6KotlinStore>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key freshness:(id<Store6KotlinFreshness>)freshness suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__4__get(dispatchReceiver:key:freshness:suspendHandler:)")));
+ (void)Skie_Suspend__5__invalidateDispatchReceiver:(id<Store6KotlinStore>)dispatchReceiver key:(id<Store6KotlinStoreKey>)key suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__5__invalidate(dispatchReceiver:key:suspendHandler:)")));
+ (void)Skie_Suspend__6__invalidateAllDispatchReceiver:(id<Store6KotlinStore>)dispatchReceiver suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__6__invalidateAll(dispatchReceiver:suspendHandler:)")));
+ (void)Skie_Suspend__7__invalidateNamespaceDispatchReceiver:(id<Store6KotlinStore>)dispatchReceiver namespace:(Store6KotlinStoreNamespace *)namespace_ suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__7__invalidateNamespace(dispatchReceiver:namespace:suspendHandler:)")));
+ (void)Skie_Suspend__8__collectDispatchReceiver:(id<Store6KotlinKotlinx_coroutines_coreFlow>)dispatchReceiver collector:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)collector suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__8__collect(dispatchReceiver:collector:suspendHandler:)")));
+ (void)Skie_Suspend__9__emitDispatchReceiver:(id<Store6KotlinKotlinx_coroutines_coreFlowCollector>)dispatchReceiver value:(id _Nullable)value suspendHandler:(Store6KotlinSkie_SuspendHandler *)suspendHandler __attribute__((swift_name("Skie_Suspend__9__emit(dispatchReceiver:value:suspendHandler:)")));
@end

__attribute__((swift_name("KotlinIllegalStateException")))
@interface Store6KotlinKotlinIllegalStateException : Store6KotlinKotlinRuntimeException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   kotlin.SinceKotlin(version="1.4")
*/
__attribute__((swift_name("KotlinCancellationException")))
@interface Store6KotlinKotlinCancellationException : Store6KotlinKotlinIllegalStateException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6KotlinKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreRunnable")))
@protocol Store6KotlinKotlinx_coroutines_coreRunnable
@required
- (void)run __attribute__((swift_name("run()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinEnumCompanion")))
@interface Store6KotlinKotlinEnumCompanion : Store6KotlinBase
@property (class, readonly, getter=shared) Store6KotlinKotlinEnumCompanion *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinArray")))
@interface Store6KotlinKotlinArray<T> : Store6KotlinBase
@property (readonly) int32_t size __attribute__((swift_name("size")));
+ (instancetype)arrayWithSize:(int32_t)size init:(T _Nullable (^)(Store6KotlinInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (T _Nullable)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (id<Store6KotlinKotlinIterator>)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(T _Nullable)value __attribute__((swift_name("set(index:value:)")));
@end

__attribute__((swift_name("KotlinFunction")))
@protocol Store6KotlinKotlinFunction
@required
@end

__attribute__((swift_name("KotlinSuspendFunction1")))
@protocol Store6KotlinKotlinSuspendFunction1 <Store6KotlinKotlinFunction>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invokeP1:(id _Nullable)p1 completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("invoke(p1:completionHandler:)")));
@end

__attribute__((swift_name("KotlinSuspendFunction0")))
@protocol Store6KotlinKotlinSuspendFunction0 <Store6KotlinKotlinFunction>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invokeWithCompletionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("invoke(completionHandler:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinUnit")))
@interface Store6KotlinKotlinUnit : Store6KotlinBase
@property (class, readonly, getter=shared) Store6KotlinKotlinUnit *shared __attribute__((swift_name("shared")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)unit __attribute__((swift_name("init()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("KotlinIterator")))
@protocol Store6KotlinKotlinIterator
@required
- (BOOL)hasNext __attribute__((swift_name("hasNext()")));
- (id _Nullable)next __attribute__((swift_name("next()")));
@end

#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
