package com.fragmind.camnode.core.data.di

import javax.inject.Qualifier

/** Work that must outlive any screen or service, such as delivering an alert. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
