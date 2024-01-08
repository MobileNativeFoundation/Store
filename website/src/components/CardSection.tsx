/**
 *  Copyright 2022 Dyte
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

import React, {ReactNode} from 'react';
import {paramCase} from 'param-case';
import clsx from 'clsx';

export function CardSection({
                                id,
                                title,
                                children,
                                description,
                                className,
                                hasSubSections = false,
                                HeadingTag = 'h3',
                            }: {
    id?: string;
    title: string;
    children: ReactNode;
    description?: ReactNode;
    hasSubSections?: boolean;
    HeadingTag?: keyof JSX.IntrinsicElements;
    className?: string;
}) {
    return (
        <div
            className={clsx(
                'homepage-section',
                hasSubSections && 'has-sub-sections',
                className
            )}
        >
            {title && <HeadingTag id={id ?? paramCase(title)}>{title}</HeadingTag>}
            {description && <p className="section-description">{description}</p>}
            <div className="section-content">{children}</div>
        </div>
    );
}
