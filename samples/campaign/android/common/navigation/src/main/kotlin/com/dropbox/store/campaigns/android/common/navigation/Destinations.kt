package com.dropbox.store.campaigns.android.common.navigation

interface Destinations {
    interface PurchaseFlow {
        interface PlanCompare : PurchaseFlow
    }

    interface ManagePlan
    interface ManageSpace
}