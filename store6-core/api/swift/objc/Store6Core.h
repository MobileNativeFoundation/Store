#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class Store6CoreFetchPlanSkip, Store6CoreFetcherResultDeleted, Store6CoreFreshnessCachedOrFetch, Store6CoreFreshnessContext, Store6CoreFreshnessLocalOnly, Store6CoreFreshnessMustBeFresh, Store6CoreFreshnessStaleIfError, Store6CoreKeyEvents, Store6CoreKeyStatus, Store6CoreKotlinArray<T>, Store6CoreKotlinEnum<E>, Store6CoreKotlinEnumCompanion, Store6CoreKotlinException, Store6CoreKotlinIllegalStateException, Store6CoreKotlinRuntimeException, Store6CoreKotlinThrowable, Store6CoreOrigin, Store6CoreStoreBuilder<K, V>, Store6CoreStoreError, Store6CoreStoreErrorConflict, Store6CoreStoreErrorConversion, Store6CoreStoreErrorFetch, Store6CoreStoreErrorFreshnessUnsatisfiable, Store6CoreStoreErrorMissing, Store6CoreStoreErrorPersistence, Store6CoreStoreException, Store6CoreStoreNamespace, Store6CoreStoreResultData<V>, Store6CoreStoreResultError, Store6CoreStoreResultLoading, Store6CoreStoreResultRevalidated, Store6CoreStoreResults;

@protocol Store6CoreBookkeeper, Store6CoreFetchPlan, Store6CoreFetcher, Store6CoreFetcherResult, Store6CoreFreshness, Store6CoreFreshnessValidator, Store6CoreKotlinComparable, Store6CoreKotlinFunction, Store6CoreKotlinIterator, Store6CoreKotlinSuspendFunction0, Store6CoreKotlinSuspendFunction1, Store6CoreKotlinx_coroutines_coreFlow, Store6CoreKotlinx_coroutines_coreFlowCollector, Store6CoreOverlay, Store6CoreSourceOfTruth, Store6CoreStore, Store6CoreStoreKey, Store6CoreStoreMeta, Store6CoreStoreResult, Store6CoreStoreRuntime, Store6CoreStoreTelemetry, Store6CoreStoreWriteHandle, Store6CoreWallClock;

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
@interface Store6CoreBase : NSObject
- (instancetype)init __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
+ (void)initialize __attribute__((objc_requires_super));
@end

@interface Store6CoreBase (Store6CoreBaseCopying) <NSCopying>
@end

__attribute__((swift_name("KotlinMutableSet")))
@interface Store6CoreMutableSet<ObjectType> : NSMutableSet<ObjectType>
@end

__attribute__((swift_name("KotlinMutableDictionary")))
@interface Store6CoreMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType>
@end

@interface NSError (NSErrorStore6CoreKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

__attribute__((swift_name("KotlinNumber")))
@interface Store6CoreNumber : NSNumber
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
@interface Store6CoreByte : Store6CoreNumber
- (instancetype)initWithChar:(char)value;
+ (instancetype)numberWithChar:(char)value;
@end

__attribute__((swift_name("KotlinUByte")))
@interface Store6CoreUByte : Store6CoreNumber
- (instancetype)initWithUnsignedChar:(unsigned char)value;
+ (instancetype)numberWithUnsignedChar:(unsigned char)value;
@end

__attribute__((swift_name("KotlinShort")))
@interface Store6CoreShort : Store6CoreNumber
- (instancetype)initWithShort:(short)value;
+ (instancetype)numberWithShort:(short)value;
@end

__attribute__((swift_name("KotlinUShort")))
@interface Store6CoreUShort : Store6CoreNumber
- (instancetype)initWithUnsignedShort:(unsigned short)value;
+ (instancetype)numberWithUnsignedShort:(unsigned short)value;
@end

__attribute__((swift_name("KotlinInt")))
@interface Store6CoreInt : Store6CoreNumber
- (instancetype)initWithInt:(int)value;
+ (instancetype)numberWithInt:(int)value;
@end

__attribute__((swift_name("KotlinUInt")))
@interface Store6CoreUInt : Store6CoreNumber
- (instancetype)initWithUnsignedInt:(unsigned int)value;
+ (instancetype)numberWithUnsignedInt:(unsigned int)value;
@end

__attribute__((swift_name("KotlinLong")))
@interface Store6CoreLong : Store6CoreNumber
- (instancetype)initWithLongLong:(long long)value;
+ (instancetype)numberWithLongLong:(long long)value;
@end

__attribute__((swift_name("KotlinULong")))
@interface Store6CoreULong : Store6CoreNumber
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value;
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value;
@end

__attribute__((swift_name("KotlinFloat")))
@interface Store6CoreFloat : Store6CoreNumber
- (instancetype)initWithFloat:(float)value;
+ (instancetype)numberWithFloat:(float)value;
@end

__attribute__((swift_name("KotlinDouble")))
@interface Store6CoreDouble : Store6CoreNumber
- (instancetype)initWithDouble:(double)value;
+ (instancetype)numberWithDouble:(double)value;
@end

__attribute__((swift_name("KotlinBoolean")))
@interface Store6CoreBoolean : Store6CoreNumber
- (instancetype)initWithBool:(BOOL)value;
+ (instancetype)numberWithBool:(BOOL)value;
@end

__attribute__((swift_name("Freshness")))
@protocol Store6CoreFreshness
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessCachedOrFetch")))
@interface Store6CoreFreshnessCachedOrFetch : Store6CoreBase <Store6CoreFreshness>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)cachedOrFetch __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreFreshnessCachedOrFetch *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessLocalOnly")))
@interface Store6CoreFreshnessLocalOnly : Store6CoreBase <Store6CoreFreshness>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)localOnly __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreFreshnessLocalOnly *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessMaxAge")))
@interface Store6CoreFreshnessMaxAge : Store6CoreBase <Store6CoreFreshness>
- (instancetype)initWithNotOlderThan:(int64_t)notOlderThan __attribute__((swift_name("init(notOlderThan:)"))) __attribute__((objc_designated_initializer));
@property (readonly) int64_t notOlderThan __attribute__((swift_name("notOlderThan")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessMustBeFresh")))
@interface Store6CoreFreshnessMustBeFresh : Store6CoreBase <Store6CoreFreshness>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)mustBeFresh __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreFreshnessMustBeFresh *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessStaleIfError")))
@interface Store6CoreFreshnessStaleIfError : Store6CoreBase <Store6CoreFreshness>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)staleIfError __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreFreshnessStaleIfError *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("KotlinComparable")))
@protocol Store6CoreKotlinComparable
@required
- (int32_t)compareToOther:(id _Nullable)other __attribute__((swift_name("compareTo(other:)")));
@end

__attribute__((swift_name("KotlinEnum")))
@interface Store6CoreKotlinEnum<E> : Store6CoreBase <Store6CoreKotlinComparable>
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) Store6CoreKotlinEnumCompanion *companion __attribute__((swift_name("companion")));
- (int32_t)compareToOther:(E)other __attribute__((swift_name("compareTo(other:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *name __attribute__((swift_name("name")));
@property (readonly) int32_t ordinal __attribute__((swift_name("ordinal")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Origin")))
@interface Store6CoreOrigin : Store6CoreKotlinEnum<Store6CoreOrigin *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) Store6CoreOrigin *memory __attribute__((swift_name("memory")));
@property (class, readonly) Store6CoreOrigin *sot __attribute__((swift_name("sot")));
@property (class, readonly) Store6CoreOrigin *fetcher __attribute__((swift_name("fetcher")));
@property (class, readonly) Store6CoreOrigin *overlay __attribute__((swift_name("overlay")));
+ (Store6CoreKotlinArray<Store6CoreOrigin *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<Store6CoreOrigin *> *entries __attribute__((swift_name("entries")));
@end


/**
 * @note annotations
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Store")))
@protocol Store6CoreStore
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearKey:(id<Store6CoreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clear(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)clearNamespaceNamespace:(Store6CoreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("clearNamespace(namespace:completionHandler:)")));
- (void)close __attribute__((swift_name("close()")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)getKey:(id<Store6CoreStoreKey>)key freshness:(id<Store6CoreFreshness>)freshness completionHandler:(void (^)(id _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("get(key:freshness:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateKey:(id<Store6CoreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidate(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invalidateNamespaceNamespace:(Store6CoreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("invalidateNamespace(namespace:completionHandler:)")));
- (id<Store6CoreKotlinx_coroutines_coreFlow>)streamKey:(id<Store6CoreStoreKey>)key freshness:(id<Store6CoreFreshness>)freshness __attribute__((swift_name("stream(key:freshness:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreBuilder")))
@interface Store6CoreStoreBuilder<K, V> : Store6CoreBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)bookkeeperBookkeeper:(id<Store6CoreBookkeeper>)bookkeeper __attribute__((swift_name("bookkeeper(bookkeeper:)")));
- (void)fetcherFetch:(id<Store6CoreKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcher(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)fetcherFetcher:(id<Store6CoreFetcher>)fetcher __attribute__((swift_name("fetcher(fetcher:)")));
- (void)fetcherOfResultFetch:(id<Store6CoreKotlinSuspendFunction1>)fetch __attribute__((swift_name("fetcherOfResult(fetch:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)freshnessValidatorValidator:(id<Store6CoreFreshnessValidator>)validator __attribute__((swift_name("freshnessValidator(validator:)")));
- (void)maxIdleKeysCount:(int32_t)count __attribute__((swift_name("maxIdleKeys(count:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)overlayOverlay:(id<Store6CoreOverlay>)overlay __attribute__((swift_name("overlay(overlay:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)persistenceSot:(id<Store6CoreSourceOfTruth>)sot __attribute__((swift_name("persistence(sot:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)telemetryTelemetry:(id<Store6CoreStoreTelemetry>)telemetry __attribute__((swift_name("telemetry(telemetry:)")));

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
- (void)wallClockWallClock:(id<Store6CoreWallClock>)wallClock __attribute__((swift_name("wallClock(wallClock:)")));
@end

__attribute__((swift_name("StoreError")))
@interface Store6CoreStoreError : Store6CoreBase
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Conflict")))
@interface Store6CoreStoreErrorConflict : Store6CoreStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@property (readonly) id<Store6CoreStoreMeta> _Nullable serverMeta __attribute__((swift_name("serverMeta")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Conversion")))
@interface Store6CoreStoreErrorConversion : Store6CoreStoreError
@property (readonly) Store6CoreKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Fetch")))
@interface Store6CoreStoreErrorFetch : Store6CoreStoreError
@property (readonly) Store6CoreKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.FreshnessUnsatisfiable")))
@interface Store6CoreStoreErrorFreshnessUnsatisfiable : Store6CoreStoreError
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Missing")))
@interface Store6CoreStoreErrorMissing : Store6CoreStoreError
@property (readonly) id<Store6CoreStoreKey> key __attribute__((swift_name("key")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreError.Persistence")))
@interface Store6CoreStoreErrorPersistence : Store6CoreStoreError
@property (readonly) Store6CoreKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString *message __attribute__((swift_name("message")));
@end

__attribute__((swift_name("KotlinThrowable")))
@interface Store6CoreKotlinThrowable : Store6CoreBase
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   kotlin.experimental.ExperimentalNativeApi
*/
- (Store6CoreKotlinArray<NSString *> *)getStackTrace __attribute__((swift_name("getStackTrace()")));
- (void)printStackTrace __attribute__((swift_name("printStackTrace()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) Store6CoreKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString * _Nullable message __attribute__((swift_name("message")));
- (NSError *)asError __attribute__((swift_name("asError()")));
@end

__attribute__((swift_name("KotlinException")))
@interface Store6CoreKotlinException : Store6CoreKotlinThrowable
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinRuntimeException")))
@interface Store6CoreKotlinRuntimeException : Store6CoreKotlinException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreException")))
@interface Store6CoreStoreException : Store6CoreKotlinRuntimeException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
- (instancetype)initWithCause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (readonly) Store6CoreStoreError *error __attribute__((swift_name("error")));
@end

__attribute__((swift_name("StoreKey")))
@protocol Store6CoreStoreKey
@required
- (NSString *)canonicalId __attribute__((swift_name("canonicalId()")));
@property (readonly, getter=namespace) Store6CoreStoreNamespace *namespace_ __attribute__((swift_name("namespace_")));
@end

__attribute__((swift_name("StoreMeta")))
@protocol Store6CoreStoreMeta
@required
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) int64_t writtenAtEpochMillis __attribute__((swift_name("writtenAtEpochMillis")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreNamespace")))
@interface Store6CoreStoreNamespace : Store6CoreBase
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end

__attribute__((swift_name("StoreResult")))
@protocol Store6CoreStoreResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultData")))
@interface Store6CoreStoreResultData<V> : Store6CoreBase <Store6CoreStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@property (readonly) BOOL isStale __attribute__((swift_name("isStale")));
@property (readonly) Store6CoreOrigin *origin __attribute__((swift_name("origin")));
@property (readonly) BOOL refreshing __attribute__((swift_name("refreshing")));
@property (readonly) V _Nullable value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultError")))
@interface Store6CoreStoreResultError : Store6CoreBase <Store6CoreStoreResult>
@property (readonly) Store6CoreStoreError *error __attribute__((swift_name("error")));
@property (readonly) BOOL servedStale __attribute__((swift_name("servedStale")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultLoading")))
@interface Store6CoreStoreResultLoading : Store6CoreBase <Store6CoreStoreResult>
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResultRevalidated")))
@interface Store6CoreStoreResultRevalidated : Store6CoreBase <Store6CoreStoreResult>
@property (readonly) int64_t age __attribute__((swift_name("age")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Bookkeeper")))
@protocol Store6CoreBookkeeper
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
- (void)advanceStaleWatermarkNamespace:(Store6CoreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("advanceStaleWatermark(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetKey:(id<Store6CoreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forget(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)forgetNamespaceNamespace:(Store6CoreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("forgetNamespace(namespace:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)markStaleKey:(id<Store6CoreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("markStale(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordFailureKey:(id<Store6CoreStoreKey>)key atEpochMillis:(int64_t)atEpochMillis completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordFailure(key:atEpochMillis:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)recordSuccessKey:(id<Store6CoreStoreKey>)key meta:(id<Store6CoreStoreMeta>)meta completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("recordSuccess(key:meta:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)statusKey:(id<Store6CoreStoreKey>)key completionHandler:(void (^)(Store6CoreKeyStatus * _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("status(key:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("FetchPlan")))
@protocol Store6CoreFetchPlan
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetchPlanConditional")))
@interface Store6CoreFetchPlanConditional : Store6CoreBase <Store6CoreFetchPlan>
- (instancetype)initWithEtag:(NSString *)etag servesResidentWhileFetching:(BOOL)servesResidentWhileFetching __attribute__((swift_name("init(etag:servesResidentWhileFetching:)"))) __attribute__((objc_designated_initializer));
@property (readonly) NSString *etag __attribute__((swift_name("etag")));
@property (readonly) BOOL servesResidentWhileFetching __attribute__((swift_name("servesResidentWhileFetching")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetchPlanFetch")))
@interface Store6CoreFetchPlanFetch : Store6CoreBase <Store6CoreFetchPlan>
- (instancetype)initWithServesResidentWhileFetching:(BOOL)servesResidentWhileFetching __attribute__((swift_name("init(servesResidentWhileFetching:)"))) __attribute__((objc_designated_initializer));
@property (readonly) BOOL servesResidentWhileFetching __attribute__((swift_name("servesResidentWhileFetching")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetchPlanSkip")))
@interface Store6CoreFetchPlanSkip : Store6CoreBase <Store6CoreFetchPlan>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)skip __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreFetchPlanSkip *shared __attribute__((swift_name("shared")));
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
@protocol Store6CoreFetcher
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)fetchKey:(id<Store6CoreStoreKey>)key etag:(NSString * _Nullable)etag completionHandler:(void (^)(id<Store6CoreFetcherResult> _Nullable, NSError * _Nullable))completionHandler __attribute__((swift_name("fetch(key:etag:completionHandler:)")));
@end

__attribute__((swift_name("FetcherResult")))
@protocol Store6CoreFetcherResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultDeleted")))
@interface Store6CoreFetcherResultDeleted : Store6CoreBase <Store6CoreFetcherResult>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)deleted __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreFetcherResultDeleted *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultError")))
@interface Store6CoreFetcherResultError : Store6CoreBase <Store6CoreFetcherResult>
- (instancetype)initWithCause:(Store6CoreKotlinThrowable *)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
@property (readonly) Store6CoreKotlinThrowable *cause __attribute__((swift_name("cause")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultNotModified")))
@interface Store6CoreFetcherResultNotModified : Store6CoreBase <Store6CoreFetcherResult>
- (instancetype)initWithEtag:(NSString * _Nullable)etag __attribute__((swift_name("init(etag:)"))) __attribute__((objc_designated_initializer));
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FetcherResultSuccess")))
@interface Store6CoreFetcherResultSuccess<V> : Store6CoreBase <Store6CoreFetcherResult>
- (instancetype)initWithValue:(V)value etag:(NSString * _Nullable)etag __attribute__((swift_name("init(value:etag:)"))) __attribute__((objc_designated_initializer));
@property (readonly) NSString * _Nullable etag __attribute__((swift_name("etag")));
@property (readonly) V value __attribute__((swift_name("value")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FreshnessContext")))
@interface Store6CoreFreshnessContext : Store6CoreBase
- (instancetype)initWithHasResidentValue:(BOOL)hasResidentValue meta:(id<Store6CoreStoreMeta> _Nullable)meta epochStale:(BOOL)epochStale freshness:(id<Store6CoreFreshness>)freshness nowEpochMillis:(int64_t)nowEpochMillis status:(Store6CoreKeyStatus * _Nullable)status __attribute__((swift_name("init(hasResidentValue:meta:epochStale:freshness:nowEpochMillis:status:)"))) __attribute__((objc_designated_initializer));
@property (readonly) BOOL epochStale __attribute__((swift_name("epochStale")));
@property (readonly) id<Store6CoreFreshness> freshness __attribute__((swift_name("freshness")));
@property (readonly) BOOL hasResidentValue __attribute__((swift_name("hasResidentValue")));
@property (readonly) id<Store6CoreStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
@property (readonly) int64_t nowEpochMillis __attribute__((swift_name("nowEpochMillis")));
@property (readonly) Store6CoreKeyStatus * _Nullable status __attribute__((swift_name("status")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("FreshnessValidator")))
@protocol Store6CoreFreshnessValidator
@required
- (id<Store6CoreFetchPlan>)planContext:(Store6CoreFreshnessContext *)context __attribute__((swift_name("plan(context:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((swift_name("KeyEvents")))
@interface Store6CoreKeyEvents : Store6CoreBase
@property (readonly) id<Store6CoreStoreKey> key __attribute__((swift_name("key")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyEvents.Deleted")))
@interface Store6CoreKeyEventsDeleted : Store6CoreKeyEvents
@property (readonly) id<Store6CoreStoreKey> key __attribute__((swift_name("key")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyEvents.Invalidated")))
@interface Store6CoreKeyEventsInvalidated : Store6CoreKeyEvents
@property (readonly) id<Store6CoreStoreKey> key __attribute__((swift_name("key")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyEvents.Written")))
@interface Store6CoreKeyEventsWritten : Store6CoreKeyEvents
@property (readonly) id<Store6CoreStoreKey> key __attribute__((swift_name("key")));
@property (readonly) Store6CoreOrigin *origin __attribute__((swift_name("origin")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KeyStatus")))
@interface Store6CoreKeyStatus : Store6CoreBase
- (instancetype)initWithMeta:(id<Store6CoreStoreMeta> _Nullable)meta lastSuccessSequence:(Store6CoreLong * _Nullable)lastSuccessSequence lastFailureAtEpochMillis:(Store6CoreLong * _Nullable)lastFailureAtEpochMillis consecutiveFailures:(int32_t)consecutiveFailures durablyStale:(BOOL)durablyStale __attribute__((swift_name("init(meta:lastSuccessSequence:lastFailureAtEpochMillis:consecutiveFailures:durablyStale:)"))) __attribute__((objc_designated_initializer));
@property (readonly) int32_t consecutiveFailures __attribute__((swift_name("consecutiveFailures")));
@property (readonly) BOOL durablyStale __attribute__((swift_name("durablyStale")));
@property (readonly) Store6CoreLong * _Nullable lastFailureAtEpochMillis __attribute__((swift_name("lastFailureAtEpochMillis")));
@property (readonly) Store6CoreLong * _Nullable lastSuccessSequence __attribute__((swift_name("lastSuccessSequence")));
@property (readonly) id<Store6CoreStoreMeta> _Nullable meta __attribute__((swift_name("meta")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("Overlay")))
@protocol Store6CoreOverlay
@required
- (id _Nullable)applyKey:(id<Store6CoreStoreKey>)key base:(id _Nullable)base __attribute__((swift_name("apply(key:base:)")));
@property (readonly) id<Store6CoreKotlinx_coroutines_coreFlow> changes __attribute__((swift_name("changes")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("SourceOfTruth")))
@protocol Store6CoreSourceOfTruth
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteKey:(id<Store6CoreStoreKey>)key completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("delete(key:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteAllWithCompletionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteAll(completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)deleteNamespaceNamespace:(Store6CoreStoreNamespace *)namespace_ completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("deleteNamespace(namespace:completionHandler:)")));
- (id<Store6CoreKotlinx_coroutines_coreFlow>)readerKey:(id<Store6CoreStoreKey>)key __attribute__((swift_name("reader(key:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)writeKey:(id<Store6CoreStoreKey>)key value:(id)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("write(key:value:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreResults")))
@interface Store6CoreStoreResults : Store6CoreBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)storeResults __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreStoreResults *shared __attribute__((swift_name("shared")));
- (Store6CoreStoreErrorConflict *)conflictServerMeta:(id<Store6CoreStoreMeta> _Nullable)serverMeta message:(NSString *)message __attribute__((swift_name("conflict(serverMeta:message:)")));
- (Store6CoreStoreErrorConversion *)conversionErrorMessage:(NSString *)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("conversionError(message:cause:)")));
- (Store6CoreStoreResultData<id> *)dataValue:(id _Nullable)value origin:(Store6CoreOrigin *)origin age:(int64_t)age isStale:(BOOL)isStale refreshing:(BOOL)refreshing __attribute__((swift_name("data(value:origin:age:isStale:refreshing:)")));
- (Store6CoreStoreResultError *)errorError:(Store6CoreStoreError *)error servedStale:(BOOL)servedStale __attribute__((swift_name("error(error:servedStale:)")));
- (Store6CoreStoreException *)exceptionError:(Store6CoreStoreError *)error cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("exception(error:cause:)")));
- (Store6CoreStoreErrorFetch *)fetchErrorMessage:(NSString *)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("fetchError(message:cause:)")));
- (Store6CoreStoreErrorFreshnessUnsatisfiable *)freshnessUnsatisfiableMessage:(NSString *)message __attribute__((swift_name("freshnessUnsatisfiable(message:)")));
- (Store6CoreStoreResultLoading *)loading __attribute__((swift_name("loading()")));
- (Store6CoreStoreErrorMissing *)missingKey:(id<Store6CoreStoreKey>)key message:(NSString *)message __attribute__((swift_name("missing(key:message:)")));
- (Store6CoreStoreErrorPersistence *)persistenceErrorMessage:(NSString *)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("persistenceError(message:cause:)")));
- (Store6CoreStoreResultRevalidated *)revalidatedAge:(int64_t)age __attribute__((swift_name("revalidated(age:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("StoreRuntime")))
@protocol Store6CoreStoreRuntime
@required
@property (readonly) id<Store6CoreKotlinx_coroutines_coreFlow> keyEvents __attribute__((swift_name("keyEvents")));
@property (readonly) id<Store6CoreStoreTelemetry> _Nullable telemetry __attribute__((swift_name("telemetry")));
@property (readonly) id<Store6CoreStoreWriteHandle> writeHandle __attribute__((swift_name("writeHandle")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("StoreTelemetry")))
@protocol Store6CoreStoreTelemetry
@required
- (void)onClearedKey:(id<Store6CoreStoreKey>)key __attribute__((swift_name("onCleared(key:)")));
- (void)onFetchFailedKey:(id<Store6CoreStoreKey>)key error:(Store6CoreStoreError *)error duration:(int64_t)duration __attribute__((swift_name("onFetchFailed(key:error:duration:)")));
- (void)onFetchStartedKey:(id<Store6CoreStoreKey>)key __attribute__((swift_name("onFetchStarted(key:)")));
- (void)onFetchSucceededKey:(id<Store6CoreStoreKey>)key duration:(int64_t)duration __attribute__((swift_name("onFetchSucceeded(key:duration:)")));
- (void)onInvalidatedKey:(id<Store6CoreStoreKey>)key __attribute__((swift_name("onInvalidated(key:)")));
- (void)onServeKey:(id<Store6CoreStoreKey>)key origin:(Store6CoreOrigin *)origin __attribute__((swift_name("onServe(key:origin:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("StoreWriteHandle")))
@protocol Store6CoreStoreWriteHandle
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)applyKey:(id<Store6CoreStoreKey>)key value:(id)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("apply(key:value:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)confirmFreshKey:(id<Store6CoreStoreKey>)key etag:(NSString * _Nullable)etag completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("confirmFresh(key:etag:completionHandler:)")));

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)markStaleKey:(id<Store6CoreStoreKey>)key completionHandler_:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("markStale(key:completionHandler_:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("TransactionalSourceOfTruth")))
@protocol Store6CoreTransactionalSourceOfTruth <Store6CoreSourceOfTruth>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)withTransactionBlock:(id<Store6CoreKotlinSuspendFunction0>)block completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("withTransaction(block:completionHandler:)")));
@end


/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
 *   kotlin.SubclassOptInRequired(markerClass=[NormalClass(value=org/mobilenativefoundation/store6/core/DelicateStoreApi)])
*/
__attribute__((swift_name("WallClock")))
@protocol Store6CoreWallClock
@required
- (int64_t)nowEpochMillis __attribute__((swift_name("nowEpochMillis()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreBuilderKt")))
@interface Store6CoreStoreBuilderKt : Store6CoreBase
+ (id<Store6CoreStore>)storeConfigure:(void (^)(Store6CoreStoreBuilder<id<Store6CoreStoreKey>, id> *))configure __attribute__((swift_name("store(configure:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StoreRuntimeKt")))
@interface Store6CoreStoreRuntimeKt : Store6CoreBase

/**
 * @note annotations
 *   org.mobilenativefoundation.store6.core.ExperimentalStoreApi
*/
+ (id<Store6CoreStoreRuntime> _Nullable)runtime:(id<Store6CoreStore>)receiver __attribute__((swift_name("runtime(_:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinEnumCompanion")))
@interface Store6CoreKotlinEnumCompanion : Store6CoreBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) Store6CoreKotlinEnumCompanion *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinArray")))
@interface Store6CoreKotlinArray<T> : Store6CoreBase
+ (instancetype)arrayWithSize:(int32_t)size init:(T _Nullable (^)(Store6CoreInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (T _Nullable)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (id<Store6CoreKotlinIterator>)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(T _Nullable)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((swift_name("KotlinIllegalStateException")))
@interface Store6CoreKotlinIllegalStateException : Store6CoreKotlinRuntimeException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end


/**
 * @note annotations
 *   kotlin.SinceKotlin(version="1.4")
*/
__attribute__((swift_name("KotlinCancellationException")))
@interface Store6CoreKotlinCancellationException : Store6CoreKotlinIllegalStateException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(Store6CoreKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlow")))
@protocol Store6CoreKotlinx_coroutines_coreFlow
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)collectCollector:(id<Store6CoreKotlinx_coroutines_coreFlowCollector>)collector completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("collect(collector:completionHandler:)")));
@end

__attribute__((swift_name("KotlinFunction")))
@protocol Store6CoreKotlinFunction
@required
@end

__attribute__((swift_name("KotlinSuspendFunction1")))
@protocol Store6CoreKotlinSuspendFunction1 <Store6CoreKotlinFunction>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invokeP1:(id _Nullable)p1 completionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("invoke(p1:completionHandler:)")));
@end

__attribute__((swift_name("KotlinSuspendFunction0")))
@protocol Store6CoreKotlinSuspendFunction0 <Store6CoreKotlinFunction>
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)invokeWithCompletionHandler:(void (^)(id _Nullable_result, NSError * _Nullable))completionHandler __attribute__((swift_name("invoke(completionHandler:)")));
@end

__attribute__((swift_name("KotlinIterator")))
@protocol Store6CoreKotlinIterator
@required
- (BOOL)hasNext __attribute__((swift_name("hasNext()")));
- (id _Nullable)next __attribute__((swift_name("next()")));
@end

__attribute__((swift_name("Kotlinx_coroutines_coreFlowCollector")))
@protocol Store6CoreKotlinx_coroutines_coreFlowCollector
@required

/**
 * @note This method converts instances of CancellationException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (void)emitValue:(id _Nullable)value completionHandler:(void (^)(NSError * _Nullable))completionHandler __attribute__((swift_name("emit(value:completionHandler:)")));
@end

#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
